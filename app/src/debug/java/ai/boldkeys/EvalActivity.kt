package ai.boldkeys

import android.app.Activity
import android.os.Bundle
import android.util.Log
import kotlin.random.Random

/** Debug only: measures the suggestion engine on typos made the way thumbs make them. Run with `am start -n ai.boldkeys/.EvalActivity` (add `--es lang fr` for French on AZERTY); results go to logcat (tag BKEval). */
class EvalActivity : Activity() {
    private companion object { val started = java.util.concurrent.atomic.AtomicBoolean(false) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (started.compareAndSet(false, true)) Thread { run(); runOnUiThread { finish() } }.start() else finish()
    }

    private var lang = "en"
    private val pos by lazy { Layouts.positions(if (lang == "fr") "azerty" else "qwerty") }
    private val col get() = pos.mapValues { it.value.first }
    private val row get() = pos.mapValues { it.value.second }
    private fun neighbours(ch: Char) = pos.keys.filter { it != ch && kotlin.math.abs(pos[it]!!.first - pos[ch]!!.first) <= 1 && kotlin.math.abs(pos[it]!!.second - pos[ch]!!.second) <= 1 }

    private fun typo(word: String, rnd: Random): String? {
        if (word.length < 3) return null
        val accented = SpellEngine.unaccent(word) != word
        val w = SpellEngine.unaccent(word)
        if (!w.all { it in pos }) return null
        if (accented && rnd.nextInt(100) < 50) return w // the commonest French slip: the accent left off
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
        lang = intent?.getStringExtra("lang") ?: "en"
        Log.i("BKEval", "language=$lang")
        val text = assets.open(Lang.wordsAsset(lang)).bufferedReader().use { it.readText() }
        val all = text.lineSequence().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList()
        val full = SpellEngine(all, Lang.hasAccents(lang))
        full.setLayout(if (lang == "fr") "azerty" else "qwerty")
        full.loadFollowers(assets.open(Lang.pairsAsset(lang)).bufferedReader().use { it.readText() })
        full.british = false
        Log.i("BKEval", "S american: colour->${full.autocorrect("colour")} realise->${full.autocorrect("realise")} centre->${full.autocorrect("centre")} travelling->${full.autocorrect("travelling")} grey->${full.autocorrect("grey")} program->${full.autocorrect("program")} suggest(colo)=${full.suggest("colo", 3).map { it.word }}")
        full.british = true
        Log.i("BKEval", "S british: color->${full.autocorrect("color")} realize->${full.autocorrect("realize")} center->${full.autocorrect("center")} traveling->${full.autocorrect("traveling")} gray->${full.autocorrect("gray")} program->${full.autocorrect("program")} suggest(colo)=${full.suggest("colo", 3).map { it.word }} next(thank)=${full.nextWords("thank", 2)} next(i'm)=${full.nextWords("i'm", 2)}")
        full.british = false
        Log.i("BKEval", "S next words: the=${full.nextWords("the", 3)} of=${full.nextWords("of", 3)} you=${full.nextWords("you", 3)} how=${full.nextWords("how", 3)} dictionary=${full.size} words")
        if (lang == "fr") Log.i("BKEval", "F ete->${full.autocorrect("ete")} ca->${full.autocorrect("ca")} tres->${full.autocorrect("tres")} deja->${full.autocorrect("deja")} etre->${full.autocorrect("etre")} francais->${full.autocorrect("francais")} ou->${full.autocorrect("ou")} a->${full.autocorrect("a")} suggest(ou)=${full.suggest("ou", 3).map { it.word }} suggest(ete)=${full.suggest("ete", 3).map { it.word }} suggest(fran)=${full.suggest("fran", 3).map { it.word }} next(je)=${full.nextWords("je", 3)} next(ne)=${full.nextWords("ne", 3)}")
        if (lang == "fr") for (t in listOf("ete", "cote", "ou", "mur")) Log.i("BKEval", "D $t: " + full.correct(t, 5).joinToString { "${it.word}/${"%.2f".format(it.score)}/${"%.2f".format(it.cost)}" } + " | complete: " + full.complete(t, 4).joinToString { "${it.word}/${"%.2f".format(it.score)}" })
        val rnd = Random(7)
        val total = all.sumOf { it.second.toLong() }
        fun pick(list: List<Pair<String, Int>>): String { var r = rnd.nextLong(list.sumOf { it.second.toLong() }); for ((w, c) in list) { r -= c; if (r < 0) return w }; return list.last().first }
        val common = all.take(20000).filter { it.first.length >= 3 && SpellEngine.unaccent(it.first).all { c -> c in pos } }

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
        val small = SpellEngine(all.take(30000), Lang.hasAccents(lang)).also { it.setLayout(if (lang == "fr") "azerty" else "qwerty") }
        val rare = all.drop(30000).filter { it.first.length >= 4 && SpellEngine.unaccent(it.first).all { c -> c in pos } }.shuffled(rnd).take(6000)
        SpellEngine.AC_COST = intent?.getFloatExtra("accost", 1.25f) ?: 1.25f; SpellEngine.AC_MIN_LOG = intent?.getFloatExtra("acmin", 9.5f) ?: 9.5f
        Log.i("BKEval", "settings: AC_COST=${SpellEngine.AC_COST} AC_MIN_LOG=${SpellEngine.AC_MIN_LOG}")
        var fixed = 0; var wrong = 0
        for ((t, w) in typos) { val a = full.autocorrect(t); if (a == w) fixed++ else if (a != null) wrong++ }
        var fixed2 = 0; var wrong2 = 0
        for ((t, w) in typos) { val a = Autofix.fix(full, t, "i said "); if (a == w) fixed2++ else if (a != null) wrong2++ }
        Log.i("BKEval", "B2 with the shipped guards: typos fixed ${pct(fixed2, typos.size)}, made worse ${pct(wrong2, typos.size)}")
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
