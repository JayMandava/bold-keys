package ai.boldkeys

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/**
 * Debug only: types correct sentences (filesDir/leak_sents.txt, one per line) through autocorrect and counts every word it changes.
 * All the sentences are right, so every change is a leak. Extra "lm" = true also runs the context model. Results go to logcat, tag BKLeak.
 */
class LeakEvalActivity : Activity() {
    private companion object { val started = java.util.concurrent.atomic.AtomicBoolean(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (started.compareAndSet(false, true)) Thread { try { run() } finally { started.set(false) }; runOnUiThread { finish() } }.start() else finish()
    }

    private fun run() {
        val e = SpellEngine(assets.open("words.tsv").bufferedReader().use { it.readText() }.lineSequence().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList())
        val useLm = intent.getBooleanExtra("lm", false)
        val lm = if (useLm) { ContextModels.init(this); ContextModels.selected()?.let { ContextLm.load(ContextModels.file(it).absolutePath) } } else null
        val sents = File(filesDir, "leak_sents.txt").readLines().filter { it.isNotBlank() }
        var words = 0; var oldChanged = 0; var newChanged = 0; var lmChanged = 0
        val oldTop = HashMap<String, Int>(); val newTop = HashMap<String, Int>(); val lmTop = HashMap<String, Int>()
        for (s in sents) {
            val toks = s.split(' ')
            val sb = StringBuilder()
            for (t in toks) {
                val bare = t.trimEnd('.', ',', '!', '?', ';', ':', '"', ')', '”')
                val word = bare.trimStart('"', '(', '“')
                val before = sb.toString() + bare.dropLast(word.length)
                sb.append(t).append(' ')
                if (word.isEmpty() || !word.all { it.isLetter() || it == '\'' }) continue
                if (!(t.length > bare.length)) { /* a word at the end of the line is committed by enter, not here, but treat alike */ }
                words++
                val old = if (word == "i") "I" else e.autocorrect(word)
                if (old != null && !old.equals(word, ignoreCase = true)) { oldChanged++; oldTop.merge("$word>$old", 1, Int::plus) }
                val new = Autofix.fix(e, word, before)
                if (new != null && !new.equals(word, ignoreCase = true)) { newChanged++; newTop.merge("$word>$new", 1, Int::plus) }
                if (lm != null && Autofix.eligible(word, before)) {
                    val left = ContextJudge.leftContext(before)
                    if (left != null) {
                        val pick = ContextJudge.judge(lm, e, left, word, new, t.length > bare.length)
                        val fin = pick ?: new
                        if (fin != null && !fin.equals(word, ignoreCase = true)) { lmChanged++; lmTop.merge("$word>$fin", 1, Int::plus); if (pick != null) Log.i("BKLeak", "CASE [$left] $word>$fin | $s") }
                    } else if (new != null && !new.equals(word, ignoreCase = true)) { lmChanged++; lmTop.merge("$word>$new", 1, Int::plus) }
                }
            }
        }
        fun top(m: Map<String, Int>) = m.entries.sortedByDescending { it.value }.take(45).joinToString(" ") { "${it.key}:${it.value}" }
        Log.i("BKLeak", "sentences=${sents.size} words=$words")
        Log.i("BKLeak", "OLD changed=$oldChanged (${"%.3f".format(100.0 * oldChanged / words)}%) top: ${top(oldTop)}")
        Log.i("BKLeak", "NEW changed=$newChanged (${"%.3f".format(100.0 * newChanged / words)}%) top: ${top(newTop)}")
        if (lm != null) Log.i("BKLeak", "LM  changed=$lmChanged (${"%.3f".format(100.0 * lmChanged / words)}%) top: ${top(lmTop)}")
    }
}
