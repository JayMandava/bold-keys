package ai.boldkeys

import android.content.Context

/** Settings edits must invalidate pending in-memory learning even when file timestamps match. */
object LearningFiles {
    fun revision(context: Context, lang: String): Long =
        context.getSharedPreferences("bk_learning", Context.MODE_PRIVATE).getLong(lang, 0)

    fun edited(context: Context, lang: String) {
        val prefs = context.getSharedPreferences("bk_learning", Context.MODE_PRIVATE)
        prefs.edit().putLong(lang, prefs.getLong(lang, 0) + 1).apply()
    }
}
