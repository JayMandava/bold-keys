package ai.boldkeys

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONObject

/** What a Speed-key letter does: open an app, open a link or action (web page, tel:, mailto:, geo:), or type a piece of text. */
class Shortcut(val type: String, val value: String, val label: String) {
    companion object { const val APP = "app"; const val LINK = "link"; const val TEXT = "text" }
}

/** The letter shortcuts, kept in the keyboard's own preferences on the phone. */
object Shortcuts {
    private const val KEY = "shortcuts"

    private fun load(c: Context) = runCatching { JSONObject(Prefs.string(c, KEY, "{}")) }.getOrDefault(JSONObject())

    fun get(c: Context, letter: Char): Shortcut? {
        val o = load(c).optJSONObject(letter.uppercaseChar().toString()) ?: return null
        return Shortcut(o.optString("t"), o.optString("v"), o.optString("l"))
    }

    fun all(c: Context): Map<Char, Shortcut> = ('A'..'Z').mapNotNull { ch -> get(c, ch)?.let { ch to it } }.toMap()

    fun set(c: Context, letter: Char, s: Shortcut?) {
        val all = load(c)
        val k = letter.uppercaseChar().toString()
        if (s == null) all.remove(k) else all.put(k, JSONObject().put("t", s.type).put("v", s.value).put("l", s.label))
        Prefs.put(c, KEY, all.toString())
    }

    /** Does what the shortcut says. Text is handed back to the caller to type; the rest opens something and returns null. */
    fun run(c: Context, s: Shortcut): String? {
        when (s.type) {
            Shortcut.TEXT -> return s.value
            Shortcut.APP -> c.packageManager.getLaunchIntentForPackage(s.value)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            Shortcut.LINK -> runCatching {
                val uri = if (s.value.contains(":")) s.value else "https://${s.value}"
                c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        return null
    }

    fun appIcon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Apps that can be opened from a launcher, for the picker. */
    fun launchableApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.filter { it.second != c.packageName }.sortedBy { it.first.lowercase() }
    }
}
