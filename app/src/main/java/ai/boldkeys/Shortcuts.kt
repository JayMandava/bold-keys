package ai.boldkeys

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import org.json.JSONObject

/** Persisted action IDs are stable: existing app/link/text assignments need no migration. */
class Shortcut(val type: String, val value: String, val label: String) {
    companion object { const val APP = "app"; const val LINK = "link"; const val TEXT = "text"; const val CONTACT = "contact"; const val DIRECT_CALL = "direct_call" }
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

    data class Result(val text: String? = null, val error: String? = null)

    /** No launch exceptions or contact details escape to logs. Permission is checked on every call. */
    fun execute(c: Context, s: Shortcut): Result {
        if (s.type == Shortcut.TEXT) return Result(text = s.value)
        return try {
            val intent = when (s.type) {
                Shortcut.APP -> c.packageManager.getLaunchIntentForPackage(s.value)
                    ?: return Result(error = "This app is unavailable. Choose another app in Speed key settings.")
                Shortcut.LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(if (s.value.contains(":")) s.value else "https://${s.value}"))
                Shortcut.CONTACT -> {
                    val uri = Uri.parse(s.value)
                    if (!SelectedContacts.isLookupUri(uri)) return Result(error = "Choose this contact again in Speed key settings.")
                    Intent(Intent.ACTION_VIEW, uri)
                }
                Shortcut.DIRECT_CALL -> {
                    if (!DirectCalls.hasPermission(c)) return Result(error = "Direct Call is off. Enable Phone permission in Speed key settings, then try again.")
                    val number = DirectCalls.safeNumber(c, s.value)
                        ?: return Result(error = "This number cannot be called with Direct Call. Choose another number in Speed key settings.")
                    Intent(Intent.ACTION_CALL, Uri.fromParts("tel", number, null))
                }
                else -> return Result(error = "Choose this shortcut again in Speed key settings.")
            }
            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            Result()
        } catch (_: SecurityException) {
            Result(error = "Shortcut access is unavailable. Review this assignment in Speed key settings.")
        } catch (_: android.content.ActivityNotFoundException) {
            Result(error = "No app is available for this shortcut. Review it in Speed key settings.")
        } catch (_: RuntimeException) {
            Result(error = "This shortcut is unavailable. Choose it again in Speed key settings.")
        }
    }

    fun run(c: Context, s: Shortcut): String? = execute(c, s).text

    fun appIcon(c: Context, pkg: String): Drawable? = runCatching { c.packageManager.getApplicationIcon(pkg) }.getOrNull()

    /** Apps that can be opened from a launcher, for the picker. */
    fun launchableApps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }.filter { it.second != c.packageName }.sortedBy { it.first.lowercase() }
    }
}
