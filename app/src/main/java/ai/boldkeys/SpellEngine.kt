package ai.boldkeys

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min

/**
 * Suggestions and autocorrect without any model: a frequency dictionary, a typo model that knows where the keys really are (a letter hit
 * next to the right one costs less than a random one), and the words the user has taught it. Fast enough to answer on every key press.
 */
class SpellEngine(entries0: List<Pair<String, Int>>, val accented: Boolean = false) {
    class Cand(val word: String, val score: Float, val cost: Float, val completion: Boolean)

    /** American -> British and back, for the pairs that exist. */
    private val usToUk = HashMap<String, String>()
    private val ukToUs = HashMap<String, String>()
    /** Which English the user writes: the other spelling is corrected to this one and left out of the suggestions. */
    var british = false

    private val words: Array<String>
    /** What each word is looked up by: the word itself, or (for a language with accents) the word without them, so "ete" finds "été". */
    private val key: Array<String>
    private val logf: FloatArray
    private val index = HashMap<String, Int>(entries0.size * 2 + 2000)
    private val byLen: Array<IntArray>
    private val alpha: IntArray // word indices in alphabetical order, for prefix lookups
    private val deletes: LongArray // (hash of the word with one letter left out, or of the word itself) << 32 | word index, sorted

    /** Words the user has typed: counted here, and treated as real words once seen [LEARN_AFTER] times. */
    private val learned = HashMap<String, Int>()
    var onLearnedChanged: (() -> Unit)? = null

    init {
        val byWord = entries0.toMap()
        val extra = ArrayList<Pair<String, Int>>()
        if (!accented) for ((us, uk) in Spelling.pairs(byWord.keys)) {
            usToUk[us] = uk; ukToUs[uk] = us
            if (uk !in byWord) extra.add(uk to maxOf(1, ((byWord[us] ?: 1) * 0.9).toInt()))
        }
        val entries = entries0 + extra
        val sorted = entries.sortedByDescending { it.second }
        words = Array(sorted.size) { sorted[it].first }
        logf = FloatArray(sorted.size) { ln(sorted[it].second.toFloat() + 1f) }
        key = if (accented) Array(words.size) { fold(words[it]) } else words
        for (i in words.indices) index[words[i]] = i
        val buckets = Array(MAX_LEN + 1) { ArrayList<Int>() }
        for (i in words.indices) if (words[i].length <= MAX_LEN) buckets[words[i].length].add(i)
        byLen = Array(MAX_LEN + 1) { buckets[it].toIntArray() }
        alpha = words.indices.sortedBy { key[it] }.toIntArray()
        val keys = LongArray(words.sumOf { (if (it.length <= MAX_LEN) it.length + 1 else 0) })
        var k = 0
        for (i in words.indices) {
            val w = key[i]
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

    /** False for the spelling the user did not choose (it is not offered, and typing it gets changed to the chosen one). */
    private fun allowed(w: String) = if (british) !usToUk.containsKey(w) else !ukToUs.containsKey(w)

    private var follow: Map<String, List<String>> = emptyMap()

    /** The next-word table: each line is a word, a tab, and the words that most often come after it. */
    fun loadFollowers(tsv: String) {
        follow = tsv.lineSequence().mapNotNull { l -> val t = l.indexOf('\t'); if (t <= 0) null else l.substring(0, t) to l.substring(t + 1).split(',') }.toMap()
    }

    /** Likely words after [prev], best first (empty when nothing is known). */
    fun nextWords(prev: String, n: Int): List<String> {
        val p = prev.lowercase()
        return (follow[p] ?: return emptyList()).filter { it != p && allowed(it) }.map { w -> if (british) usToUk[w] ?: w else w }.filter(SuggestionSafety::allows).take(n)
    }

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
        val p = fold(prefix.lowercase())
        if (p.isEmpty()) return emptyList()
        var lo = 0; var hi = alpha.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (key[alpha[mid]] < p) lo = mid + 1 else hi = mid }
        val best = ArrayList<Cand>()
        var i = lo; var scanned = 0
        while (i < alpha.size && key[alpha[i]].startsWith(p) && scanned < 4000) {
            val w = words[alpha[i]]
            if (w.length > p.length && allowed(w) && SuggestionSafety.allows(w)) {
                val s = logf[alpha[i]] - 0.15f * (w.length - p.length) // a short completion beats a long one of similar frequency
                insert(best, Cand(w, s, 0f, true), n)
            }
            i++; scanned++
        }
        for ((w, c) in learned) if (c >= LEARN_AFTER && SuggestionSafety.allows(w) && fold(w).startsWith(p) && w.length > p.length) insert(best, Cand(w, learnedLog(w)!! - 0.15f * (w.length - p.length), 0f, true), n)
        return best
    }

    /** Words close to [typed] in what a thumb might have hit, best first. Empty if nothing is close enough. */
    fun correct(typed: String, n: Int): List<Cand> = correctionCandidates(typed, n, true)

    /** Unfiltered competitors are retained for conservative automatic decisions, never displayed. */
    internal fun correctionCandidates(typed: String, n: Int, safeOnly: Boolean = false): List<Cand> {
        val t = typed.lowercase()
        if (t.isEmpty() || t.length > MAX_LEN) return emptyList()
        val maxCost = if (t.length <= 3) 1.05f else if (t.length <= 5) 1.55f else 2.05f
        val best = ArrayList<Cand>()
        val found = HashSet<Int>()
        val ft = fold(t)
        lookup(ft, found)
        for (j in ft.indices) lookup(ft.substring(0, j) + ft.substring(j + 1), found)
        for (i in found) {
            val w = words[i]
            if (w == t || !allowed(w) || (safeOnly && !SuggestionSafety.allows(w))) continue
            val c = cost(t, w, maxCost)
            if (c <= maxCost) insert(best, Cand(w, logf[i] - COST_WEIGHT * c, c, false), n)
        }
        for ((w, cnt) in learned) if (cnt >= LEARN_AFTER && (!safeOnly || SuggestionSafety.allows(w)) && w != t && abs(w.length - t.length) <= 2) {
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
        // an unknown word that is just a known one without its accents ("ete") should offer that word first
        if (accented && !isWord(typed)) { val f = fold(typed.lowercase()); out.sortByDescending { fold(it.word) == f } }
        return out.take(n)
    }

    /**
     * What to type instead of [typed] when the user presses space after it, or null to leave it alone.
     * Only words the dictionary does not know are changed, and only to something clearly closer than the alternatives.
     */
    fun autocorrect(typed: String, maxCost: Float = AC_COST): String? {
        if (!SuggestionSafety.allows(typed)) return null // a listed word typed deliberately is never sanitized
        val t = typed.lowercase()
        // the other English: "colour" for someone who writes American, "color" for someone who writes British
        (if (british) usToUk[t] else ukToUs[t])?.let { return it.takeIf(SuggestionSafety::allows) }
        if (t.length < 2 || isWord(t)) return null
        if (!t.all { it.isLetter() || it == '\'' }) return null
        val c = correctionCandidates(t, 2)
        if (c.isEmpty()) return null
        val top = c[0]
        if (!SuggestionSafety.allows(top.word)) return null
        if (top.cost > maxCost) return null // only fix what is plainly a slip
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

    // where the keys sit on the chosen layout; columns line up as on the Bold (Z sits under S), see Layouts.positions
    private var pos: Map<Char, Pair<Int, Int>> = Layouts.positions("qwerty")

    /** Which wrong keys count as a slip away from the right one follows the layout in use. */
    fun setLayout(layout: String) { pos = Layouts.positions(layout) }

    private val scratch = Array(3) { FloatArray(MAX_LEN + 3) }

    private fun near(a: Char, b: Char): Boolean {
        val p = pos[fold(a)] ?: return false; val q = pos[fold(b)] ?: return false
        return abs(p.first - q.first) <= 1 && abs(p.second - q.second) <= 1
    }

    private fun subCost(a: Char, b: Char, first: Boolean): Float {
        if (a == b) return 0f
        if (accented && fold(a) == fold(b)) return ACCENT // the right letter without (or with the wrong) accent: a slip, not a different word
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

    /** The letter without its accent (only for a language that has them; English text passes through untouched). */
    private fun fold(c: Char): Char = if (!accented) c else FOLD[c] ?: c
    private fun fold(s: String): String = if (!accented) s else buildString(s.length) { for (c in s) append(fold(c)) }

    companion object {
        private const val ACCENT = 0.05f
        private val FOLD: Map<Char, Char> = HashMap<Char, Char>().also { m ->
            for ((base, accented) in listOf('a' to "àâäæ", 'e' to "éèêë", 'i' to "îï", 'o' to "ôöœ", 'u' to "ùûü", 'y' to "ÿ", 'c' to "ç", 's' to "ß")) for (c in accented) m[c] = base
        }
        /** [s] without its accents (for tests and tools). */
        fun unaccent(s: String): String = s.map { FOLD[it] ?: it }.joinToString("")
        const val MAX_LEN = 24
        const val LEARN_AFTER = 2
        private const val INS = 1f
        private const val DBL = 0.7f
        private const val TRANS = 0.6f
        var COST_WEIGHT = 3.2f
        var TIE_MARGIN = 0.8f
        var AC_COST = 1.25f // measured on the phone: fixes 79% of typos; see EvalActivity
        var AC_MIN_LOG = 9.5f

        fun fromTsv(text: String, accented: Boolean = false): SpellEngine = SpellEngine(text.lineSequence().mapNotNull { l ->
            val t = l.indexOf('\t'); if (t <= 0) null else l.substring(0, t) to (l.substring(t + 1).toIntOrNull() ?: 1)
        }.toList(), accented)
    }
}
