package ai.boldkeys

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Suggestions and autocorrect without any model: a frequency dictionary, a typo model that knows where the keys really are (a letter hit
 * next to the right one costs less than a random one), and the words the user has taught it. Fast enough to answer on every key press.
 */
class SpellEngine(entries: List<Pair<String, Int>>) {
    class Cand(val word: String, val score: Float, val cost: Float, val completion: Boolean)

    private val words: Array<String>
    private val logf: FloatArray
    private val index = HashMap<String, Int>(entries.size * 2)
    private val byLen: Array<IntArray>
    private val alpha: IntArray // word indices in alphabetical order, for prefix lookups
    private val deletes: LongArray // (hash of the word with one letter left out, or of the word itself) << 32 | word index, sorted

    /** Words the user has typed: counted here, and treated as real words once seen [LEARN_AFTER] times. */
    private val learned = HashMap<String, Int>()
    var onLearnedChanged: (() -> Unit)? = null

    init {
        val sorted = entries.sortedByDescending { it.second }
        words = Array(sorted.size) { sorted[it].first }
        logf = FloatArray(sorted.size) { ln(sorted[it].second.toFloat() + 1f) }
        for (i in words.indices) index[words[i]] = i
        val buckets = Array(MAX_LEN + 1) { ArrayList<Int>() }
        for (i in words.indices) if (words[i].length <= MAX_LEN) buckets[words[i].length].add(i)
        byLen = Array(MAX_LEN + 1) { buckets[it].toIntArray() }
        alpha = words.indices.sortedBy { words[it] }.toIntArray()
        val keys = LongArray(words.sumOf { (if (it.length <= MAX_LEN) it.length + 1 else 0) })
        var k = 0
        for (i in words.indices) {
            val w = words[i]
            if (w.length > MAX_LEN) continue
            keys[k++] = pack(w.hashCode(), i)
            for (j in w.indices) keys[k++] = pack((w.substring(0, j) + w.substring(j + 1)).hashCode(), i)
        }
        keys.sort()
        deletes = keys
    }

    private fun pack(h: Int, i: Int) = ((h.toLong() and 0xffffffffL) shl 32) or i.toLong()

    /** Indices of words that share the form [v] (the word itself, or the word with one letter left out). */
    private fun lookup(v: String, into: HashSet<Int>) {
        val lo0 = (v.hashCode().toLong() and 0xffffffffL) shl 32
        var lo = 0; var hi = deletes.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (deletes[mid] < lo0) lo = mid + 1 else hi = mid }
        var i = lo
        while (i < deletes.size && (deletes[i] ushr 32) == (lo0 ushr 32) && into.size < 4000) { into.add((deletes[i] and 0xffffffffL).toInt()); i++ }
    }

    val size get() = words.size

    fun loadLearned(map: Map<String, Int>) { learned.clear(); learned.putAll(map) }
    fun learnedSnapshot(): Map<String, Int> = HashMap(learned)

    /** True for dictionary words and for words the user has typed often enough. */
    fun isWord(w: String): Boolean {
        val l = w.lowercase()
        return index.containsKey(l) || (learned[l] ?: 0) >= LEARN_AFTER
    }

    /** The user typed [w] and moved on without it being changed: remember it. */
    fun noteTyped(w: String) {
        val l = w.lowercase()
        if (l.length < 2 || index.containsKey(l) || !l.all { it.isLetter() || it == '\'' }) return
        val n = (learned[l] ?: 0) + 1
        learned[l] = n
        if (n == LEARN_AFTER) onLearnedChanged?.invoke()
    }

    /** The user undid an autocorrect to keep this word: it counts as typed enough times to be trusted at once. */
    fun teach(w: String) { val l = w.lowercase(); if (l.length >= 2 && !index.containsKey(l)) { learned[l] = maxOf(learned[l] ?: 0, LEARN_AFTER); onLearnedChanged?.invoke() } }

    fun forget(w: String) { if (learned.remove(w.lowercase()) != null) onLearnedChanged?.invoke() }

    private fun learnedLog(w: String): Float? = learned[w]?.takeIf { it >= LEARN_AFTER }?.let { 9.5f + ln(it.toFloat()) } // user words rank like common words

    /** Up to [n] words that start with [prefix], most likely first. */
    fun complete(prefix: String, n: Int): List<Cand> {
        val p = prefix.lowercase()
        if (p.isEmpty()) return emptyList()
        var lo = 0; var hi = alpha.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (words[alpha[mid]] < p) lo = mid + 1 else hi = mid }
        val best = ArrayList<Cand>()
        var i = lo; var scanned = 0
        while (i < alpha.size && words[alpha[i]].startsWith(p) && scanned < 4000) {
            val w = words[alpha[i]]
            if (w.length > p.length) {
                val s = logf[alpha[i]] - 0.15f * (w.length - p.length) // a short completion beats a long one of similar frequency
                insert(best, Cand(w, s, 0f, true), n)
            }
            i++; scanned++
        }
        for ((w, c) in learned) if (c >= LEARN_AFTER && w.startsWith(p) && w.length > p.length) insert(best, Cand(w, learnedLog(w)!! - 0.15f * (w.length - p.length), 0f, true), n)
        return best
    }

    /** Words close to [typed] in what a thumb might have hit, best first. Empty if nothing is close enough. */
    fun correct(typed: String, n: Int): List<Cand> {
        val t = typed.lowercase()
        if (t.isEmpty() || t.length > MAX_LEN) return emptyList()
        val maxCost = if (t.length <= 3) 1.05f else if (t.length <= 5) 1.55f else 2.05f
        val best = ArrayList<Cand>()
        val found = HashSet<Int>()
        lookup(t, found)
        for (j in t.indices) lookup(t.substring(0, j) + t.substring(j + 1), found)
        for (i in found) {
            val w = words[i]
            if (w == t) continue
            val c = cost(t, w, maxCost)
            if (c <= maxCost) insert(best, Cand(w, logf[i] - COST_WEIGHT * c, c, false), n)
        }
        for ((w, cnt) in learned) if (cnt >= LEARN_AFTER && w != t && abs(w.length - t.length) <= 2) {
            val c = cost(t, w, maxCost)
            if (c <= maxCost) insert(best, Cand(w, learnedLog(w)!! - COST_WEIGHT * c, c, false), n)
        }
        return best
    }

    /** The strip: the best completions and corrections together. */
    fun suggest(typed: String, n: Int = 3): List<Cand> {
        if (typed.isEmpty()) return emptyList()
        val out = ArrayList<Cand>()
        for (c in complete(typed, 4)) insert(out, c, n + 2)
        for (c in correct(typed, 4)) insert(out, c, n + 2)
        // a word the user already typed correctly is not worth offering back to them
        out.removeAll { it.word == typed.lowercase() }
        return out.take(n)
    }

    /**
     * What to type instead of [typed] when the user presses space after it, or null to leave it alone.
     * Only words the dictionary does not know are changed, and only to something clearly closer than the alternatives.
     */
    fun autocorrect(typed: String): String? {
        val t = typed.lowercase()
        if (t.length < 2 || isWord(t)) return null
        if (!t.all { it.isLetter() || it == '\'' }) return null
        val c = correct(t, 2)
        if (c.isEmpty()) return null
        val top = c[0]
        if (top.cost > AC_COST) return null // only fix what is plainly a slip
        if (top.score + COST_WEIGHT * top.cost < AC_MIN_LOG) return null // and only to a word people actually use
        if (c.size > 1 && (top.score - c[1].score) < TIE_MARGIN && top.cost >= c[1].cost) return null // not when two words are about equally likely
        return top.word
    }

    private fun insert(list: ArrayList<Cand>, c: Cand, n: Int) {
        if (list.any { it.word == c.word }) return
        var i = list.size
        while (i > 0 && list[i - 1].score < c.score) i--
        if (i >= n) return
        list.add(i, c)
        if (list.size > n) list.removeAt(list.size - 1)
    }

    // ---- the typo model: Damerau-Levenshtein where a miss next to the right key is cheap and the first letter is trusted

    private val pos = HashMap<Char, Pair<Int, Int>>().also {
        val rows = listOf("qwertyuiop" to 0, "asdfghjkl" to 0, "zxcvbnm" to 1) // columns line up as on the Bold: Z sits under S
        rows.forEachIndexed { r, (s, off) -> s.forEachIndexed { c, ch -> it[ch] = (c + off) to r } }
    }

    private val scratch = Array(3) { FloatArray(MAX_LEN + 3) }

    private fun near(a: Char, b: Char): Boolean {
        val p = pos[a] ?: return false; val q = pos[b] ?: return false
        return abs(p.first - q.first) <= 1 && abs(p.second - q.second) <= 1
    }

    private fun subCost(a: Char, b: Char, first: Boolean): Float {
        if (a == b) return 0f
        val base = if (near(a, b)) 0.55f else 1f
        return if (first) base + 0.5f else base
    }

    /** Cost of turning what was typed ([a]) into the word ([b]); anything above [limit] is reported as just over it. */
    private fun cost(a: String, b: String, limit: Float): Float {
        val n = a.length; val m = b.length
        if (abs(n - m) > 2) return limit + 1f
        var prev2 = scratch[0]; var prev = scratch[1]; var cur = scratch[2]
        for (j in 0..m) prev[j] = j * INS
        for (i in 1..n) {
            cur[0] = i * INS
            var rowMin = cur[0]
            for (j in 1..m) {
                var v = prev[j - 1] + subCost(a[i - 1], b[j - 1], i == 1 && j == 1)
                v = min(v, prev[j] + (if (i > 1 && a[i - 1] == a[i - 2]) DBL else INS)) // an extra repeated letter is a common slip
                v = min(v, cur[j - 1] + INS)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + TRANS)
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > limit) return limit + 1f
            val t = prev2; prev2 = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    companion object {
        const val MAX_LEN = 24
        const val LEARN_AFTER = 2
        private const val INS = 1f
        private const val DBL = 0.7f
        private const val TRANS = 0.6f
        var COST_WEIGHT = 3.2f
        var TIE_MARGIN = 0.8f
        var AC_COST = 1.25f // measured on the phone: fixes 79% of typos; see EvalActivity
        var AC_MIN_LOG = 9.5f

        fun fromTsv(text: String): SpellEngine = SpellEngine(text.lineSequence().mapNotNull { l ->
            val t = l.indexOf('\t'); if (t <= 0) null else l.substring(0, t) to (l.substring(t + 1).toIntOrNull() ?: 1)
        }.toList())
    }
}
