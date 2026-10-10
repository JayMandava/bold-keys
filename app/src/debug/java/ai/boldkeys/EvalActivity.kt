package ai.boldkeys

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlin.random.Random

/** Debug only: measures the suggestion engine on typos made the way thumbs make them. Run with `am start -n ai.boldkeys/.EvalActivity`; results go to logcat (tag BKEval). */
class EvalActivity : Activity() {
    private companion object { val started = java.util.concurrent.atomic.AtomicBoolean(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (started.compareAndSet(false, true)) Thread { run(); runOnUiThread { finish() } }.start() else finish()
    }

    private val rows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")
    private val col = HashMap<Char, Int>().also { m -> rows.forEachIndexed { r, s -> s.forEachIndexed { c, ch -> m[ch] = c + (if (r == 2) 1 else 0) } } }
    private val row = HashMap<Char, Int>().also { m -> rows.forEachIndexed { r, s -> s.forEach { ch -> m[ch] = r } } }
    private fun neighbours(ch: Char) = col.keys.filter { it != ch && kotlin.math.abs(col[it]!! - col[ch]!!) <= 1 && kotlin.math.abs(row[it]!! - row[ch]!!) <= 1 }

    private fun typo(w: String, rnd: Random): String? {
        if (w.length < 3 || !w.all { it in col }) return null
        val i = rnd.nextInt(w.length)
        return when (rnd.nextInt(100)) {
            in 0..34 -> w.substring(0, i) + neighbours(w[i]).random(rnd) + w.substring(i + 1) // hit the next key over
            in 35..54 -> if (w.length > 3) w.removeRange(i, i + 1) else null // dropped a letter
            in 55..69 -> w.substring(0, i + 1) + w[i] + w.substring(i + 1) // doubled a letter
            in 70..84 -> if (i < w.length - 1) w.substring(0, i) + w[i + 1] + w[i] + w.substring(i + 2) else null // swapped two
            else -> w.substring(0, i) + neighbours(w[i]).random(rnd) + w.substring(i) // an extra letter from a neighbouring key
        }
    }

    private fun run() {
        val text = assets.open("words.tsv").bufferedReader().use { it.readText() }
        val all = text.lineSequence().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList()
        val full = SpellEngine(all)
        full.loadFollowers(assets.open("pairs.tsv").bufferedReader().use { it.readText() })
        full.british = false
        Log.i("BKEval", "S american: colour->${full.autocorrect("colour")} realise->${full.autocorrect("realise")} centre->${full.autocorrect("centre")} travelling->${full.autocorrect("travelling")} grey->${full.autocorrect("grey")} program->${full.autocorrect("program")} suggest(colo)=${full.suggest("colo", 3).map { it.word }}")
        full.british = true
        Log.i("BKEval", "S british: color->${full.autocorrect("color")} realize->${full.autocorrect("realize")} center->${full.autocorrect("center")} traveling->${full.autocorrect("traveling")} gray->${full.autocorrect("gray")} program->${full.autocorrect("program")} suggest(colo)=${full.suggest("colo", 3).map { it.word }} next(thank)=${full.nextWords("thank", 2)} next(i'm)=${full.nextWords("i'm", 2)}")
        full.british = false
        Log.i("BKEval", "S next words: the=${full.nextWords("the", 3)} of=${full.nextWords("of", 3)} you=${full.nextWords("you", 3)} how=${full.nextWords("how", 3)} dictionary=${full.size} words")
        val rnd = Random(7)
        val total = all.sumOf { it.second.toLong() }
        fun pick(list: List<Pair<String, Int>>): String { var r = rnd.nextLong(list.sumOf { it.second.toLong() }); for ((w, c) in list) { r -= c; if (r < 0) return w }; return list.last().first }
        val common = all.take(20000).filter { it.first.length >= 3 && it.first.all { c -> c in col } }

        // typos on common words, made once
        val typos = ArrayList<Pair<String, String>>() // (typed, intended)
        var real = 0
        while (typos.size < 3000) {
            val w = pick(common); val t = typo(w, rnd) ?: continue
            if (full.isWord(t)) real++ else typos.add(t to w)
        }
        Log.i("BKEval", "A ${typos.size + real} typos made; $real were real words (left alone by design)")
        var top1 = 0; var top3 = 0
        for ((t, w) in typos) { val s = full.suggest(t, 3).map { it.word }; if (s.firstOrNull() == w) top1++; if (w in s) top3++ }
        Log.i("BKEval", "A strip: right word first ${pct(top1, typos.size)}, among the three ${pct(top3, typos.size)}")

        // real words the dictionary does not know (rare words, names): how often would autocorrect wrongly change them?
        val small = SpellEngine(all.take(30000))
        val rare = all.drop(30000).filter { it.first.length >= 4 && it.first.all { c -> c in col } }.shuffled(rnd).take(6000)
        SpellEngine.AC_COST = 1.25f; SpellEngine.AC_MIN_LOG = 9.5f
        var fixed = 0; var wrong = 0
        for ((t, w) in typos) { val a = full.autocorrect(t); if (a == w) fixed++ else if (a != null) wrong++ }
        var changed = 0
        for ((w, _) in rare) if (small.autocorrect(w) != null) changed++
        Log.i("BKEval", "B chosen setting: typos fixed ${pct(fixed, typos.size)}, made worse ${pct(wrong, typos.size)}; rare real words wrongly changed ${pct(changed, rare.size)}")

        // C: speed
        val words = common.map { it.first }
        val t0 = System.nanoTime(); var k = 0
        for (i in 0 until 600) { val w = words[rnd.nextInt(words.size)]; val p = w.substring(0, maxOf(1, w.length - rnd.nextInt(2))); full.suggest(p, 3); k++ }
        Log.i("BKEval", "C speed: ${"%.1f".format((System.nanoTime() - t0) / 1e6 / k)} ms per suggest() over $k calls, dictionary ${full.size} words")
    }

    private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else "%.1f%%".format(100.0 * a / b)
}
