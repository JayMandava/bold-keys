package ai.boldkeys

import android.content.Context
import android.content.res.Configuration

/** Everything the user can change, kept in one small preferences file on the phone. */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("bk", Context.MODE_PRIVATE)

    fun string(c: Context, key: String, def: String) = sp(c).getString(key, def) ?: def
    fun bool(c: Context, key: String, def: Boolean) = sp(c).getBoolean(key, def)
    fun float(c: Context, key: String, def: Float) = sp(c).getFloat(key, def)
    fun put(c: Context, key: String, v: Any) {
        val e = sp(c).edit()
        when (v) { is String -> e.putString(key, v); is Boolean -> e.putBoolean(key, v); is Float -> e.putFloat(key, v) }
        e.apply()
    }

    // keyboard look
    fun theme(c: Context) = string(c, "theme", "dark") // dark, light, auto
    fun isDark(c: Context) = when (theme(c)) {
        "light" -> false
        "auto" -> (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> true
    }
    fun backlightMode(c: Context) = string(c, "backlight", "auto") // off, on, auto (follows the phone's light sensor)
    fun backlightColor(c: Context) = string(c, "backlightColor", "white")
    fun backlightLevel(c: Context) = float(c, "backlightLevel", 0.6f)

    // typing
    fun autocorrect(c: Context) = bool(c, "autocorrect", true)
    fun suggestions(c: Context) = bool(c, "suggestions", true)
    fun autoCapitals(c: Context) = bool(c, "autocaps", true)
    fun doubleSpace(c: Context) = bool(c, "doubleSpace", true)
    fun nextWord(c: Context) = bool(c, "nextWord", true)
    fun spelling(c: Context) = string(c, "spelling", "us") // us or uk

    // symbols and emoji
    fun emojiPages(c: Context) = bool(c, "emojiPages", true)
    fun emoticons(c: Context) = bool(c, "emoticons", true)

    /** The glow colours the backlight can use, as RGB. */
    val BACKLIGHT_COLORS = linkedMapOf(
        "white" to 0xFFEAF2FF.toInt(), "amber" to 0xFFFFB347.toInt(), "blue" to 0xFF4DA3FF.toInt(), "green" to 0xFF5CF29A.toInt(),
    )
}
