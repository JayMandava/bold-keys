package ai.boldkeys

/** What differs between the languages the dictionary comes in. */
object Lang {
    fun wordsAsset(lang: String) = if (lang == "fr") "words_fr.tsv" else "words.tsv"
    fun pairsAsset(lang: String) = if (lang == "fr") "pairs_fr.tsv" else "pairs.tsv"
    fun learnedName(lang: String) = if (lang == "fr") "learned_fr.tsv" else "learned.tsv" // English keeps the name it always had
    fun hasAccents(lang: String) = lang == "fr"

    /** How a sentence usually starts, offered before anything is typed. */
    fun starters(lang: String) = if (lang == "fr") listOf("Je", "Le") else listOf("I", "The")

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
