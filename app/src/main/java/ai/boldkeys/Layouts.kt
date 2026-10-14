package ai.boldkeys

enum class Kind { CHAR, SHIFT, DELETE, ENTER, SPACE, LAYER, SYM, SPEED }

/** One key: what it shows, what it types, and how wide it is relative to the others in its row (rows add up to 10). */
class Key(val label: String, val text: String = label.lowercase(), val weight: Float = 1f, val kind: Kind = Kind.CHAR)

/** The Bold 9900's keys, row for row as printed on the phone. */
object Layouts {
    private fun letters(s: String) = s.map { Key(it.toString()) }

    private fun layoutRows(top: String, home: String, bottom: String): List<List<Key>> = listOf(
        letters(top),
        letters(home) + Key("del", "", 1f, Kind.DELETE),
        listOf(Key("alt", "", 1f, Kind.LAYER)) + letters(bottom) + Key("$", "$") + Key("enter", "", 1f, Kind.ENTER),
        listOf(
            Key("shift", "", 1.5f, Kind.SHIFT), Key("0", "0", 1.5f), Key("space", " ", 4f, Kind.SPACE),
            Key("sym", "", 1.5f, Kind.SYM), Key("speed", "", 1.5f, Kind.SPEED),
        ),
    )

    /** The Bold's own order. */
    val letterRows: List<List<Key>> = layoutRows("QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM")

    /** AZERTY: A and Z swap places with Q and W, and M moves down beside N, so every row still adds up to ten keys. */
    private val azertyRows: List<List<Key>> = layoutRows("AZERTYUIOP", "QSDFGHJKL", "WXCVBNM")

    /** The layouts on offer: the id kept in the settings, and its name. */
    val LAYOUTS = listOf("qwerty" to "QWERTY", "azerty" to "AZERTY")

    fun rows(layout: String): List<List<Key>> = if (layout == "azerty") azertyRows else letterRows

    /** Where each letter sits as (column, row), for judging which wrong keys are a slip away from the right one. Alt takes the first place of the third row, as on the phone. */
    fun positions(layout: String): Map<Char, Pair<Int, Int>> {
        val out = HashMap<Char, Pair<Int, Int>>()
        rows(layout).forEachIndexed { r, row -> row.forEachIndexed { c, k -> if (k.kind == Kind.CHAR && k.label.length == 1 && k.label[0].isLetter()) out[k.label[0].lowercaseChar()] = c to r } }
        return out
    }

    /** The languages on offer: the id kept in the settings, and its name. */
    val LANGUAGES = listOf("auto" to "Automatic", "en" to "English", "fr" to "Français")

    /** What press-and-hold on a letter offers, most common first. */
    val accents: Map<Char, String> = mapOf(
        'a' to "àâäáãåæ", 'e' to "éèêë", 'i' to "îïíì", 'o' to "ôöòóœõ", 'u' to "ùûüú", 'c' to "ç", 'y' to "ÿý", 'n' to "ñ", 's' to "ß",
    )

    /** The pages of the SYM picker (emoji have their own picker, from the emoji key). */
    val symbolPages: List<List<String>> = listOf(
        listOf("!", "?", ".", ",", ";", ":", "'", "\"", "(", ")", "-", "_", "/", "\\", "@", "#", "$", "%", "&", "*"),
        listOf("+", "=", "<", ">", "[", "]", "{", "}", "~", "^", "|", "`", "€", "£", "¥", "¢", "°", "±", "×", "÷"),
        listOf("§", "¶", "©", "®", "™", "•", "…", "¿", "¡", "«", "»", "é", "è", "ê", "à", "ç", "ñ", "ü", "ö", "ß"),
    )

    /** Text emoticons that turn into emoji. Those that cannot be part of a normal word change as soon as they are typed; the rest wait for a space or punctuation. */
    val emoticonsNow = mapOf(
        ":)" to "🙂", ":-)" to "🙂", ":(" to "🙁", ":-(" to "🙁", ";)" to "😉", ";-)" to "😉", "<3" to "❤️", ":*" to "😘", ":'(" to "😢", "^_^" to "😊",
    )
    val emoticonsOnSpace = mapOf(
        ":D" to "😀", ":-D" to "😀", ":P" to "😛", ":p" to "😛", ":-P" to "😛", ":O" to "😮", ":o" to "😮", ":/" to "😕", ":|" to "😐", "xD" to "😆", "XD" to "😆",
    )
}

/**
 * The small symbol printed on a key: what it types with Alt, exactly as on the phone.
 * Q # · W 1 · E 2 · R 3 · T ( · Y ) · U _ · I - · O + · P @   /   A * · S 4 · D 5 · F 6 · G / · H : · J ; · K ' · L "   /   Z 7 · X 8 · C 9 · V ? · B ! · N , · M .
 */
fun secondaryFor(row: Int, col: Int): String? = when (row) {
    0 -> "#123()_-+@".getOrNull(col)?.toString()
    1 -> "*456/:;'\"".getOrNull(col)?.toString()
    2 -> "789?!,.".getOrNull(col - 1)?.toString() // Alt sits first in this row
    else -> null
}
