package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ContextualSuggestionsTest {
    @Test fun contextReachesBeyondTheFourMostFrequentCompletions() {
        val entries = (1..35).map { "reach${('a'.code + it / 26).toChar()}${('a'.code + it % 26).toChar()}" to 1_000_000 } + ("request" to 100000)
        val e = SpellEngine(entries)
        e.loadFollowers("pull\trequest")
        assertFalse(e.complete("re", 4).any { it.word == "request" })
        assertEquals("request", e.suggest("re", 2, "pull").first().word)
        assertEquals(2, e.suggest("re", 2, "pull").size)
    }

    @Test fun preferenceCountsKnownWordsAndUsefulPairsWithoutChangingAutomaticScores() {
        val e = SpellEngine(listOf("hello" to 1_000_000, "help" to 900000, "test" to 50000))
        val automatic = e.correctionCandidates("hellp", 5).map { Triple(it.word, it.score, it.cost) }
        val fix = e.autocorrect("hellp")
        repeat(12) { e.noteTyped("help", "please", selected = true) }
        assertEquals(13, e.learnedSnapshot()["help"])
        assertEquals("help", e.suggest("hel", 2, "please").first().word)
        assertEquals(listOf("help"), e.nextWords("please", 2))
        assertEquals(automatic, e.correctionCandidates("hellp", 5).map { Triple(it.word, it.score, it.cost) })
        assertEquals(fix, e.autocorrect("hellp"))
        val restored = SpellEngine(emptyList()).apply { loadLearned(LearningData.parse(LearningData.encode(e.learningSnapshot()))) }
        assertEquals(e.learningSnapshot(), restored.learningSnapshot())
        assertEquals(listOf("help"), restored.nextWords("please", 2))
        restored.forget("help")
        assertTrue(restored.learningSnapshot().isEmpty())
    }

    @Test fun supplementalWordsAreSuggestionOnlyAndEveryOutputSourceIsFiltered() {
        val e = SpellEngine(listOf("shimmer" to 1000))
        e.loadFollowers("unit\ttests,testing")
        e.loadSupplement("kotlin\tcoroutines\nunit\tcoverage\nneutral\tfuck,shimmer")
        assertTrue(e.suggest("cor", 2).any { it.word == "coroutines" })
        assertFalse(e.isWord("coroutines"))
        assertNull(e.autocorrect("coroutines"))
        assertEquals(listOf("tests", "testing", "coverage"), e.nextWords("unit", 10))
        e.loadLearned(mapOf("fuck" to 255, "neutral fuck" to 255, "shimmer" to 255))
        assertTrue(e.suggest("fuc", 32, "neutral").all { SuggestionSafety.allows(it.word) })
        assertTrue(e.nextWords("neutral", 32).all(SuggestionSafety::allows))
    }

    @Test fun teachingNotifiesObserversAfterTheWordIsTrusted() {
        val e = SpellEngine(emptyList())
        var observed = 0
        e.onLearnedChanged = { observed = e.learnedSnapshot()["zorbword"] ?: 0 }
        e.teach("zorbword")
        assertEquals(SpellEngine.LEARN_AFTER, observed)
    }

    @Test fun malformedOverlongAndNegativeRequestsAreHarmless() {
        val e = SpellEngine(listOf("hello" to 1000))
        for (bad in listOf("", "a".repeat(10000), "two words", "bad\tword", "123", "a@b", "'")) {
            e.noteTyped(bad, "hello"); e.teach(bad)
            assertTrue(e.suggest(bad, 2).isEmpty())
            assertTrue(e.complete(bad, 2).isEmpty())
            assertTrue(e.correct(bad, 2).isEmpty())
        }
        assertTrue(e.learningSnapshot().isEmpty())
        assertTrue(e.suggest("hel", -1).isEmpty())
        assertTrue(e.nextWords("hello", -1).isEmpty())
        e.loadLearned(mapOf("a".repeat(50) to 3, "two  words" to 2, "hello" to -3, "hello world" to Int.MAX_VALUE))
        assertEquals(mapOf("hello world" to LearningData.MAX_COUNT), e.learningSnapshot())
    }

    private fun token(i: Int): String = "word" + buildString { var j = i; do { append('a' + j % 26); j /= 26 } while (j > 0) }

    @Test fun capsSurviveSaturationMigrationAndClearing() {
        val e = SpellEngine(emptyList())
        repeat(5000) { e.noteTyped(token(it), token(it + 1), selected = true) }
        repeat(1000) { e.noteTyped("stable", "context") }
        assertTrue(e.learnedSnapshot().size <= LearningData.MAX_WORDS)
        assertTrue(e.learningSnapshot().size <= LearningData.MAX_WORDS + LearningData.MAX_PAIRS)
        assertEquals(LearningData.MAX_COUNT, e.learnedSnapshot()["stable"])
        val encoded = LearningData.encode(e.learningSnapshot())
        assertTrue(encoded.toByteArray().size < LearningData.MAX_FILE_BYTES)
        assertEquals(e.learningSnapshot(), LearningData.parse(encoded))
        e.loadLearned(emptyMap())
        assertTrue(e.learningSnapshot().isEmpty()); assertTrue(e.nextWords("context", 2).isEmpty())
        e.noteTyped("fresh", "new")
        assertEquals(mapOf("fresh" to 1, "new fresh" to 1), e.learningSnapshot())
    }

    @Test fun maximumUnicodeRecordsRemainReadable() {
        fun word(i: Int) = "漢".repeat(22) + ('一'.code + i / 100).toChar() + ('一'.code + i % 100).toChar()
        val data = LinkedHashMap<String, Int>()
        repeat(LearningData.MAX_WORDS) { data[word(it)] = 255 }
        repeat(LearningData.MAX_PAIRS) { data["${word(it)} ${word(it + 1)}"] = 255 }
        val encoded = LearningData.encode(data)
        assertTrue(encoded.toByteArray().size <= LearningData.MAX_FILE_BYTES)
        assertEquals(data, LearningData.parse(encoded))
    }

    @Test fun contextNeverJoinsSentencesIdentifiersOrNewlines() {
        assertEquals("hello", LearningData.previousWord("we say hello "))
        for (text in listOf("hello. ", "hello\n", "hello\n ", "user@host ", "path/name ", "hello, ", "word")) {
            assertEquals(text, "", LearningData.previousWord(text))
        }
    }

    @Test fun simultaneousScoringLearningAndSnapshotsUseOneEngineSafely() {
        val e = SpellEngine(listOf("hello" to 1_000_000, "help" to 900000, "held" to 50000))
        val reference = e.correctionCandidates("hellp", 5).map { it.word to it.cost }
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (0..3).map { job -> executor.submit {
                repeat(250) {
                    when(job) {
                        0 -> e.noteTyped("help", "please")
                        1 -> assertEquals(reference, e.correctionCandidates("hellp", 5).map { it.word to it.cost })
                        2 -> assertTrue(e.suggest("hel", 2, "please").all { SuggestionSafety.allows(it.word) })
                        3 -> { LearningData.encode(e.learningSnapshot()); e.setLayout("qwerty") }
                    }
                }
            } }
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally { executor.shutdownNow() }
    }
}
