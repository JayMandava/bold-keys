package ai.boldkeys

/**
 * Whether autocorrect may touch a word at all, and what to type instead. The dictionary only knows common lower-case English, so anything
 * that looks like a name, an acronym, a handle, a web address or a version number is left exactly as typed.
 */
object Autofix {
    /** Characters that, right before a word, mean it is part of something else: @handle, #tag, a/path, name.domain, snake_case, 1-2, v1.2 */
    private const val GLUE = "@#/\\._-:+&=%"

    /** [before] is everything typed before [word] (any length; only the end is looked at). Returns the replacement in lower case, or null to leave it. */
    fun fix(e: SpellEngine, word: String, before: String): String? {
        if (!eligible(word, before, e.accented)) return null
        if (isKnownForm(e, word)) return e.autocorrect(word, 0f) // leave it, except for the UK/US spelling switch
        // a capital at the start of a sentence may be a name (Fadil, Suni): only fix a plain slip there, never a bigger change
        // two-letter words are often abbreviations (np, ty, rn, fr): only a plain slip counts there (a neighbour key, a swap, a doubled letter)
        val cap = if (word.length <= 2) SHORT_COST else SpellEngine.AC_COST
        return e.autocorrect(word, if (word[0].isUpperCase()) minOf(cap, NAME_COST) else cap)
    }

    /** Cost above which a capitalised word is left alone: a neighbour-key slip or a swap passes, a rewrite does not. */
    private const val NAME_COST = 0.75f
    private const val SHORT_COST = 0.7f

    private val ABBREVIATIONS = setOf("mr", "mrs", "ms", "dr", "jr", "sr", "st", "vs", "etc", "eg", "ie", "ok", "tv", "pc", "ac", "dc", "ph", "pm", "am", "ai", "id", "iq", "un", "uk", "us", "usa", "eu", "ceo", "faq")
    private val CLITICS = listOf("'s", "'d", "'ll", "'re", "'ve", "'m", "'t")

    /** Words the dictionary does not list but that are plainly fine: possessives, contractions, and plurals of known words. */
    fun isKnownForm(e: SpellEngine, word: String): Boolean {
        val w = word.lowercase()
        if (w in ABBREVIATIONS) return true
        if (w.endsWith("s'") && e.isWord(w.dropLast(1))) return true
        for (c in CLITICS) if (w.endsWith(c) && w.length > c.length + 1 && e.isWord(w.dropLast(c.length))) return true
        if (w.length >= 5 && w.endsWith("s") && e.isWord(w.dropLast(1))) return true
        return false
    }

    /** False for words that must not be changed by anything, the dictionary or the context model. */
    fun eligible(word: String, before: String, accents: Boolean = false): Boolean {
        if (word.length < 2) return false
        if (!accents && word.any { it.code > 127 }) return false // accented or non-English letters: not the English dictionary's business
        val prev = before.lastOrNull()
        if (prev != null && (prev in GLUE || prev.isDigit() || prev.isLetter())) return false
        if (word.any { it.isUpperCase() }) {
            if (word.drop(1).any { it.isUpperCase() }) return false // an acronym or camelCase: NASA, iPhone, YouTube
            if (!sentenceStart(before)) return false // a capital in mid-sentence is a name
        }
        return true
    }

    /** True at the start of the text or after a full stop, question mark, exclamation mark or line break. */
    fun sentenceStart(before: String): Boolean {
        val t = before.trimEnd(' ', '"', '\'', '(', '“', '‘')
        return t.isEmpty() || t.last() in ".!?\n" || before.endsWith("\n")
    }
}
