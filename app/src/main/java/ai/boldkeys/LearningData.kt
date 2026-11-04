package ai.boldkeys

import java.util.Locale

/** Shared format for the existing private learned.tsv file. Pair keys contain one space. */
object LearningData {
    const val MAX_WORDS = 1024
    const val MAX_PAIRS = 2048
    const val MAX_COUNT = 255
    // Worst case: 24 BMP letters × 3 UTF-8 bytes, 3072 records including two-word keys.
    const val MAX_FILE_BYTES = 512 * 1024L

    fun validWord(w: String): Boolean = w.length in 1..SpellEngine.MAX_LEN &&
        w.first().isLetter() && w.last().isLetter() && w.all { it.isLetter() || it == '\'' }

    fun sanitize(map: Map<String, Int>): Map<String, Int> {
        val words = LinkedHashMap<String, Int>()
        val pairs = LinkedHashMap<String, Int>()
        for ((raw, count) in map) {
            val key = raw.lowercase(Locale.ROOT)
            val parts = key.split(' ')
            if (count <= 0 || parts.size !in 1..2 || !parts.all(::validWord) || (parts.size == 2 && !parts.all(SuggestionSafety::allows))) continue
            val target = if (parts.size == 1) words else pairs
            target.remove(key); target[key] = count.coerceAtMost(MAX_COUNT)
            val limit = if (parts.size == 1) MAX_WORDS else MAX_PAIRS
            if (target.size > limit) target.remove(target.keys.first())
        }
        return words.apply { putAll(pairs) }
    }

    fun parse(text: String): Map<String, Int> = sanitize(text.lineSequence().take(MAX_WORDS + MAX_PAIRS + 1024).mapNotNull {
        val p = it.split('\t')
        if (p.size == 2) p[1].toIntOrNull()?.let { n -> p[0] to n } else null
    }.toMap())

    fun encode(map: Map<String, Int>): String = sanitize(map).entries.joinToString("") { "${it.key}\t${it.value}\n" }

    /** Accept only a complete immediately preceding token separated by horizontal whitespace. */
    fun previousWord(before: String): String {
        if (before.isEmpty() || before.last() !in " \t") return ""
        val trimmed = before.trimEnd(' ', '\t')
        val token = trimmed.takeLastWhile { !it.isWhitespace() }
        return token.lowercase(Locale.ROOT).takeIf { validWord(it) && SuggestionSafety.allows(it) }.orEmpty()
    }
}
