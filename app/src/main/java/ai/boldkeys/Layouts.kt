package ai.boldkeys

enum class Kind { CHAR, SHIFT, DELETE, ENTER, SPACE, LAYER }

/** One key: what it shows, what it types, and how wide it is relative to the others in its row (rows add up to 10). */
class Key(val label: String, val text: String = label.lowercase(), val weight: Float = 1f, val kind: Kind = Kind.CHAR)

/** The Bold-style layouts: four rows, the letter keys all the same size, Alt for numbers and symbols. */
object Layouts {
    private fun letters(s: String) = s.map { Key(it.toString()) }
    private fun symbols(s: String) = s.map { Key(it.toString(), it.toString()) }

    val letterRows: List<List<Key>> = listOf(
        letters("QWERTYUIOP"),
        letters("ASDFGHJKL") + Key("del", "", 1f, Kind.DELETE),
        listOf(Key("shift", "", 1.5f, Kind.SHIFT)) + letters("ZXCVBNM") + Key("enter", "", 1.5f, Kind.ENTER),
        listOf(Key("alt", "", 1.5f, Kind.LAYER), Key(","), Key("space", " ", 4.5f, Kind.SPACE), Key("."), Key("?"), Key("!")),
    )

    val symbolRows: List<List<Key>> = listOf(
        symbols("1234567890"),
        symbols("@#\$%&-+()") + Key("del", "", 1f, Kind.DELETE),
        listOf(Key("=", "=", 1.5f)) + symbols("*\"':;/_") + Key("enter", "", 1.5f, Kind.ENTER),
        listOf(Key("alt", "", 1.5f, Kind.LAYER), Key(",", ","), Key("space", " ", 4.5f, Kind.SPACE), Key(".", "."), Key("?", "?"), Key("!", "!")),
    )
}

/** The symbol printed small in a letter key's top-right corner: what Alt types, as on the real keys. */
fun secondaryFor(row: Int, col: Int): String? {
    val sym = Layouts.symbolRows
    return when (row) {
        0 -> sym[0].getOrNull(col)?.label
        1 -> sym[1].getOrNull(col)?.takeIf { it.kind == Kind.CHAR }?.label
        2 -> sym[2].getOrNull(col)?.takeIf { it.kind == Kind.CHAR }?.label // ZXCVBNM sit one key in, like the symbols beside them
        else -> null
    }
}
