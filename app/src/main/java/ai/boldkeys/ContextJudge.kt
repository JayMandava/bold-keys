package ai.boldkeys

/**
 * Decides, from the sentence so far, which word the user meant. The dictionary proposes candidates; the model only scores them.
 * Two jobs: choosing between the dictionary's candidates for a misspelling, and catching a real word used for another
 * (their/there, your/you're). The thresholds are the ones measured in EvalActivity, not guesses.
 */
object ContextJudge {
    /** How much the model's opinion counts against the dictionary's own score when ranking spelling candidates. */
    var ALPHA = 1.5f
    /** A real word is swapped only when the model likes the other one this much more (log-likelihood). */
    var REAL_MARGIN = 4f
    /** For a misspelling the dictionary alone would leave: the context must favour one candidate over the next by this much to correct it. */
    var OPEN_GAP = 4f
    const val MIN_CONTEXT_WORDS = 2

    private val SETS = listOf(
        listOf("their", "there", "they're"), listOf("your", "you're"), listOf("its", "it's"), listOf("to", "too"), listOf("then", "than"),
        listOf("were", "where", "we're"), listOf("whose", "who's"), listOf("lose", "loose"), listOf("affect", "effect"), listOf("hear", "here"),
        listOf("by", "buy"), listOf("no", "know"), listOf("weather", "whether"), listOf("quiet", "quite"), listOf("accept", "except"),
        listOf("of", "off"), listOf("form", "from"), listOf("right", "write"), listOf("whole", "hole"), listOf("peace", "piece"),
    )
    private val confusables = HashMap<String, List<String>>().also { m -> SETS.forEach { s -> s.forEach { w -> m[w] = s } } }

    /** The text to give the model: the last few words before the one being judged, or null when there is too little to go on. */
    fun leftContext(before: String): String? {
        val t = before.trimEnd()
        val tail = if (t.length > 120) t.substring(t.length - 120).substringAfter(' ') else t
        return if (tail.isBlank() || tail.trim().split(Regex("\\s+")).size < MIN_CONTEXT_WORDS) null else tail
    }

    /**
     * The word to use in place of [word], or null to keep what is there. [fixed] is what the dictionary alone would type
     * (null if it would leave [word] alone). Lower case in, lower case out.
     */
    fun judge(lm: ContextLm, engine: SpellEngine, left: String, word: String, fixed: String?): String? {
        val w = word.lowercase()
        confusables[w]?.let { set ->
            val cands = listOf(w) + set.filter { it != w }
            val s = lm.score(left, cands)
            var best = 0
            for (i in 1 until cands.size) if (s[i] > s[best]) best = i
            return if (best != 0 && s[best] - s[0] > REAL_MARGIN) cands[best] else null
        }
        if (engine.isWord(w)) return null // a word the dictionary knows is only swapped for the confusable pairs above
        val c = engine.correct(w, 5)
        if (c.isEmpty() || (fixed == null && c.size < 2)) return null
        val s = lm.score(left, c.map { it.word })
        val fused = FloatArray(c.size) { c[it].score + ALPHA * s[it] }
        var best = 0
        for (i in c.indices) if (fused[i] > fused[best]) best = i
        if (fixed != null) return if (c[best].word != fixed.lowercase()) c[best].word else null
        // the dictionary alone would not touch this word: correct it only when the sentence makes one candidate clearly the best
        val second = fused.indices.filter { it != best }.maxOfOrNull { fused[it] } ?: return null
        return if (fused[best] - second > OPEN_GAP) c[best].word else null
    }
}
