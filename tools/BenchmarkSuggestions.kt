import ai.boldkeys.*
import java.io.File
import kotlin.system.measureNanoTime

/** Synthetic/public-dictionary checks on the Linux JVM, never a claim about phone latency. */
fun main(args: Array<String>) {
    val assets = File(args[0])
    fun percentile(times: List<Long>, p: Double) = times.sorted()[(times.size * p).toInt().coerceAtMost(times.lastIndex)] / 1e6
    fun word(i: Int): String = "sample" + buildString { var j = i; do { append('a' + j % 26); j /= 26 } while (j > 0) }
    for (lang in listOf("en", "fr", "de")) {
        val suffix = if (lang == "en") "" else "_$lang"
        val text = File(assets, "words$suffix.tsv").readText()
        val old = BaselineSpellEngine.fromTsv(text, lang != "en")
        val now = SpellEngine.fromTsv(text, lang != "en")
        val followers = File(assets, "pairs$suffix.tsv").readText()
        old.loadFollowers(followers); now.loadFollowers(followers)
        if (lang == "en") now.loadSupplement(File(assets, "suggestion_phrases_en.tsv").readText())
        val dictionary = text.lineSequence().map { it.substringBefore('\t') }.filter { it.length in 3..20 }.toList()
        val cases = dictionary.take(2000).flatMap { listOf(it, it.dropLast(1), it.dropLast(1) + "x") }
        var changedAutomatic = 0
        for (typed in cases) if (old.autocorrect(typed) != now.autocorrect(typed)) {
            changedAutomatic++
            if (changedAutomatic <= 20) println("automatic_diff lang=$lang typed=$typed baseline=${old.autocorrect(typed)} current=${now.autocorrect(typed)}")
        }
        check(changedAutomatic == 0) { "$lang: $changedAutomatic automatic replacements changed" }
        val prefixes = dictionary.take(200).map { it.take(3) }
        repeat(3) { for (p in prefixes) { old.suggest(p, 2); now.suggest(p, 2, "please") } }
        val oldTimes = mutableListOf<Long>(); val nowTimes = mutableListOf<Long>()
        repeat(5) { for (p in prefixes) {
            oldTimes += measureNanoTime { old.suggest(p, 2) }
            nowTimes += measureNanoTime { now.suggest(p, 2, "please") }
        } }
        repeat(3500) { now.noteTyped(word(it), "please", selected = true) }
        val saturatedTimes = mutableListOf<Long>()
        repeat(3) { for (p in prefixes) saturatedTimes += measureNanoTime { now.suggest(p, 2, "please") } }
        println("$lang automatic_equivalence=${cases.size}/${cases.size} baseline_p50_ms=${percentile(oldTimes, .5)} baseline_p95_ms=${percentile(oldTimes, .95)} current_p50_ms=${percentile(nowTimes, .5)} current_p95_ms=${percentile(nowTimes, .95)} saturated_p50_ms=${percentile(saturatedTimes, .5)} saturated_p95_ms=${percentile(saturatedTimes, .95)}")
        check(percentile(nowTimes, .95) < 30 && percentile(saturatedTimes, .95) < 40) { "JVM latency budget exceeded" }
        if (lang == "en") {
            now.loadLearned(emptyMap())
            val ranking = listOf("pull" to ("re" to "request"), "unit" to ("te" to "tests"), "database" to ("mi" to "migration"),
                "kotlin" to ("co" to "coroutines"), "sounds" to ("go" to "good"), "thanks" to ("ag" to "again"),
                "code" to ("rev" to "review"), "memory" to ("lea" to "leak"), "local" to ("lea" to "learning"))
            var beforeHits = 0; var afterHits = 0
            for ((context, pair) in ranking) {
                val (prefix, expected) = pair
                val before = old.suggest(prefix, 2).map { it.word }
                val after = now.suggest(prefix, 2, context).map { it.word }
                if (expected in before) beforeHits++
                if (expected in after) afterHits++
                println("synthetic context=$context prefix=$prefix expected=$expected baseline=$before current=$after")
            }
            println("synthetic_recall_at2 baseline=$beforeHits/${ranking.size} current=$afterHits/${ranking.size}")
            check(afterHits == ranking.size)
        }
    }
}
