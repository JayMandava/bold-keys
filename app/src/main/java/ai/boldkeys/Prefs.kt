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
    fun theme(c: Context) = string(c, "theme", "dark").let { id ->
        if (KeyColour.choices.any { it.first == id }) id else "dark"
    }
    fun keyTheme(c: Context) = KeyTheme(KeyColour.resolve(
        theme(c), (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES,
    ))
    fun isDark(c: Context) = keyTheme(c).dark
    /** How tall the keys are: small, normal, large or xlarge. The width is always the screen's, so a bigger key is a taller one. */
    fun keySize(c: Context) = string(c, "keySize", "normal")
    fun keyScale(c: Context) = when (keySize(c)) { "small" -> 0.9f; "large" -> 1.12f; "xlarge" -> 1.25f; else -> 1f }
    fun backlightMode(c: Context) = string(c, "backlight", "auto") // off, on, auto (follows the phone's light sensor)
    fun backlightColor(c: Context) = string(c, "backlightColor", "white")
    fun backlightLevel(c: Context) = float(c, "backlightLevel", 0.6f)

    // typing
    fun autocorrect(c: Context) = bool(c, "autocorrect", true)
    fun suggestions(c: Context) = bool(c, "suggestions", true)
    fun autoCapitals(c: Context) = bool(c, "autocaps", true)
    fun doubleSpace(c: Context) = bool(c, "doubleSpace", true)
    fun nextWord(c: Context) = bool(c, "nextWord", true)
    fun contextModel(c: Context) = bool(c, "contextModel", false) // needs a downloaded model, so off until the user turns it on
    fun spelling(c: Context) = string(c, "spelling", "us") // us or uk
    fun layout(c: Context) = string(c, "layout", "qwerty") // qwerty, qwertz or azerty
    fun languageChoice(c: Context) = string(c, "language", "auto") // auto, en, fr or de
    /** The language the dictionary and suggestions use: what the user chose, or (on auto) the one the layout belongs to. */
    fun language(c: Context) = languageChoice(c).let { if (it == "auto") (when (layout(c)) { "azerty" -> "fr"; "qwertz" -> "de"; else -> "en" }) else it }

    // symbols and emoji
    fun emojiPages(c: Context) = bool(c, "emojiPages", true)
    fun emoticons(c: Context) = bool(c, "emoticons", true)

    /** The symbol on the currency key: what the user chose, or (on auto) the one the phone's region uses. */
    fun currencyChoice(c: Context) = string(c, "currency", "auto")
    fun currency(c: Context) = currencyChoice(c).let { if (it == "auto") regionCurrency() else it }
    /** The region's currency sign, when it is a single character ($, £, €, ¥, ₹ ...); a longer one (kr, zł) would not fit the key, so it falls back to $. */
    fun regionCurrency(): String = runCatching {
        val locale = java.util.Locale.getDefault()
        java.util.Currency.getInstance(locale).getSymbol(locale)
    }.getOrNull()?.takeIf { it.length == 1 } ?: "$"
    /** Hold the $ key and slide up or down to change the volume (a quick tap still types the currency sign). */
    fun volumeSlide(c: Context) = bool(c, "volumeSlide", true)

    /** The glow colours the backlight can use, as RGB. */
    val BACKLIGHT_COLORS = linkedMapOf(
        "white" to 0xFFEAF2FF.toInt(), "amber" to 0xFFFFB347.toInt(), "blue" to 0xFF4DA3FF.toInt(), "green" to 0xFF5CF29A.toInt(),
    )
}
