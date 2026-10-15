package ai.boldkeys

/** What differs between the languages the dictionary comes in. */
object Lang {
    fun wordsAsset(lang: String) = if (lang == "en") "words.tsv" else "words_$lang.tsv"
    fun pairsAsset(lang: String) = if (lang == "en") "pairs.tsv" else "pairs_$lang.tsv"
    fun learnedName(lang: String) = if (lang == "en") "learned.tsv" else "learned_$lang.tsv" // English keeps the name it always had
    fun hasAccents(lang: String) = lang != "en"

    /** How a sentence usually starts, offered before anything is typed. */
    fun starters(lang: String) = when (lang) { "fr" -> listOf("Je", "Le"); "de" -> listOf("Ich", "Der"); else -> listOf("I", "The") }

    private val ELISIONS = setOf("c", "d", "j", "l", "m", "n", "s", "t", "qu", "jusqu", "lorsqu", "puisqu", "quelqu", "quoiqu", "aujourd")

    /** French glues c', l', j', qu' and the like onto the next word: only what follows the apostrophe is the word to look up. */
    fun stripElision(word: String, lang: String): String {
        if (lang != "fr") return word
        val i = maxOf(word.lastIndexOf('\''), word.lastIndexOf('’'))
        if (i < 0) return word
        val stem = word.substring(0, i).substringAfterLast('\'').substringAfterLast('’').lowercase()
        return if (stem in ELISIONS) word.substring(i + 1) else word
    }
}
