package ai.boldkeys

enum class Kind { CHAR, SHIFT, DELETE, ENTER, SPACE, LAYER, SYM, SPEED }

/** One key: what it shows, what it types, and how wide it is relative to the others in its row (rows add up to 10). */
class Key(val label: String, val text: String = label.lowercase(), val weight: Float = 1f, val kind: Kind = Kind.CHAR)

/** The Bold 9900's keys, row for row as printed on the phone. */
object Layouts {
    private fun letters(s: String) = s.map { Key(it.toString()) }

    val letterRows: List<List<Key>> = listOf(
        letters("QWERTYUIOP"),
        letters("ASDFGHJKL") + Key("del", "", 1f, Kind.DELETE),
        listOf(Key("alt", "", 1f, Kind.LAYER)) + letters("ZXCVBNM") + Key("$", "$") + Key("enter", "", 1f, Kind.ENTER),
        listOf(
            Key("shift", "", 1.5f, Kind.SHIFT), Key("0", "0", 1.5f), Key("space", " ", 4f, Kind.SPACE),
            Key("sym", "", 1.5f, Kind.SYM), Key("speed", "", 1.5f, Kind.SPEED),
        ),
    )

    /** How many of the picker's first pages are symbols; the rest are emoji, which the user can switch off. */
    const val SYMBOL_ONLY = 3

    /** The pages of the SYM picker. */
    val symbolPages: List<List<String>> = listOf(
        listOf("!", "?", ".", ",", ";", ":", "'", "\"", "(", ")", "-", "_", "/", "\\", "@", "#", "$", "%", "&", "*"),
        listOf("+", "=", "<", ">", "[", "]", "{", "}", "~", "^", "|", "`", "€", "£", "¥", "¢", "°", "±", "×", "÷"),
        listOf("§", "¶", "©", "®", "™", "•", "…", "¿", "¡", "«", "»", "é", "è", "ê", "à", "ç", "ñ", "ü", "ö", "ß"),
        // emoji live at the end of the cycle: nothing on the keys shows them, as on the original
        listOf("😀", "😃", "😄", "😁", "😆", "😅", "😂", "🤣", "😊", "😇", "🙂", "😉", "😍", "🥰", "😘", "😋", "😎", "🤔", "😐", "😴"),
        listOf("😢", "😭", "😡", "😱", "😳", "🤗", "🙄", "😬", "😮", "🥳", "😏", "😔", "😞", "😤", "🤯", "🥺", "😷", "🤒", "😈", "💀"),
        listOf("👍", "👎", "👏", "🙏", "💪", "👌", "✌️", "🤝", "👋", "🤞", "❤️", "💔", "🔥", "✨", "🎉", "💯", "⭐", "👀", "🎂", "☕"),
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
