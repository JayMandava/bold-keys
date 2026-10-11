package ai.boldkeys

import android.app.Activity
import android.os.Bundle
import android.util.Log
import java.io.File

/** Debug only: runs the shipped judge in the app on a fresh case file (filesDir/app_cases.tsv: kind, left context, typed, intended) and logs to BKCtx. */
class ContextEvalActivity : Activity() {
    private companion object { val started = java.util.concurrent.atomic.AtomicBoolean(false) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (started.compareAndSet(false, true)) Thread { try { run() } finally { started.set(false) }; runOnUiThread { finish() } }.start() else finish()
    }

    private fun run() {
        ContextModels.init(this)
        val m = ContextModels.selected() ?: run { Log.e("BKCtx", "no model installed"); return }
        val lm = ContextLm.load(ContextModels.file(m).absolutePath) ?: run { Log.e("BKCtx", "model failed to load"); return }
        val e = SpellEngine(assets.open("words.tsv").bufferedReader().use { it.readText() }.lineSequence().filter { it.contains('\t') }.map { it.substringBefore('\t') to it.substringAfter('\t').toInt() }.toList())
        val rows = File(filesDir, "app_cases.tsv").readLines().filter { it.isNotBlank() }.map { it.split('\t') }
        val margin = intent.getFloatExtra("margin", ContextJudge.REAL_MARGIN); ContextJudge.REAL_MARGIN = margin; ContextJudge.OPEN_GAP = intent.getFloatExtra("gap", ContextJudge.OPEN_GAP)
        var typoN = 0; var typoDict = 0; var typoCtx = 0; var wrongN = 0; var fixed = 0; var rightN = 0; var changed = 0; var ms = 0L
        for ((kind, left, typed, gold) in rows) {
            val fix = Autofix.fix(e, typed, left + " ")
            val t0 = System.nanoTime()
            val pick = ContextJudge.judge(lm, e, left, typed, fix)
            ms += (System.nanoTime() - t0) / 1_000_000
            when (kind) {
                "typo" -> { typoN++; typoDict += (fix == gold).toInt(); typoCtx += ((pick ?: fix) == gold).toInt() }
                "real_wrong" -> { wrongN++; fixed += ((pick ?: typed) == gold).toInt() }
                else -> { rightN++; changed += ((pick ?: typed) != gold).toInt() }
            }
        }
        Log.i("BKCtx", "model=${m.name} margin=$margin gap=${ContextJudge.OPEN_GAP} typos dict=${100.0 * typoDict / typoN}% ctx=${100.0 * typoCtx / typoN}% | real fixed=${100.0 * fixed / wrongN}% wrongly changed=${100.0 * changed / rightN}% | ${ms / rows.size} ms per case (n=${rows.size})")
    }
    private fun Boolean.toInt() = if (this) 1 else 0
}
