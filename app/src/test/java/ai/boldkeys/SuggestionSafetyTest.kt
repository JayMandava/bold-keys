package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SuggestionSafetyTest {
    @Test fun wholeFormsNormalizeWithoutSubstringOrAccentStripping() {
        for (word in listOf("FUCKING", "merde", "ENCULE\u0301", "SCHEISSE", "scheiße", "nigger", "pornographie", "fuck’s", "pussy", "cock", "couilles", "schwanz", "fuckin", "merdé", "scheißkerl", "scheisskerl")) {
            assertFalse(word, SuggestionSafety.allows(word))
        }
        for (word in listOf("assistant", "classic", "cocktail", "Scunthorpe", "condition", "mère", "scheint", "dich", "Dick", "dick", "con", "bite", "facial", "pédiatre", "pédagogique", "pedigree", "pédale", "Pedro", "sex", "sexual", "sexuel", "sexuelle", "sexuell", "penis", "vagina", "breast", "cancer", "contraception", "pregnancy", "santé", "santé mentale", "Gesundheit", "Brust", "Schwangerschaft")) {
            assertTrue(word, SuggestionSafety.allows(word))
        }
        val old = Locale.getDefault()
        try { Locale.setDefault(Locale.forLanguageTag("tr")); assertFalse(SuggestionSafety.allows("SHIT")) }
        finally { Locale.setDefault(old) }
    }

    @Test fun suggestionsBackfillAndLearnedCountsNeverOverride() {
        val e = SpellEngine(listOf("shit" to 1000000, "ship" to 1000, "shine" to 900, "she" to 800))
        e.loadLearned(mapOf("shitting" to 500, "shimmer" to 5))
        assertEquals(setOf("ship", "shine", "shimmer"), e.complete("shi", 3).map { it.word }.toSet())
        assertTrue(e.correct("shitt", 5).all { SuggestionSafety.allows(it.word) })
        assertTrue(e.suggest("shi", 5).all { SuggestionSafety.allows(it.word) })
        assertTrue(e.isWord("shit")); assertTrue(e.isWord("shitting"))
        assertEquals(500, e.learnedSnapshot()["shitting"])
    }

    @Test fun neutralContextsKeepSafeFollowersAcrossLanguages() {
        val e = SpellEngine(emptyList())
        e.loadFollowers("secteur\tmerde,qui,public\nnamens\tscheiße,ken,anna\ninstigating\tfucking,change,debate")
        assertEquals(listOf("qui", "public"), e.nextWords("secteur", 2))
        assertEquals(listOf("ken", "anna"), e.nextWords("namens", 2))
        assertEquals(listOf("change", "debate"), e.nextWords("instigating", 2))
    }

    @Test fun unsafeWinnerCannotPromoteWeakerAutomaticReplacement() {
        val e = SpellEngine(listOf("shit" to 1000000, "ship" to 10000))
        assertEquals("shit", e.correctionCandidates("shitt", 2).first().word)
        assertNull(e.autocorrect("shitt"))
        assertNull(Autofix.fix(e, "shitt", "a "))
        assertNull(e.autocorrect("shit"))
        e.teach("fuck"); assertTrue(e.isWord("fuck")); assertTrue(e.complete("fuc", 3).isEmpty())
    }

    @Test fun localizedFormsAndMedicalAlternativesSurvive() {
        for ((blocked, safe, prefix) in listOf(Triple("merde", "mère", "mer"), Triple("scheiße", "scheint", "schei"), Triple("pornographie", "port", "por"))) {
            val e = SpellEngine(listOf(blocked to 1000000, safe to 1000), true)
            assertEquals(listOf(safe), e.complete(prefix, 2).map { it.word })
        }
        val e = SpellEngine(listOf("sexual" to 10000, "health" to 10000, "pregnancy" to 10000))
        assertEquals("sexual", e.complete("sexu", 2).first().word)
    }

    @Test fun modelNeverReceivesBlockedAlternativesOrPromotesAfterTheirRemoval() {
        val e = SpellEngine(listOf("shit" to 1000000, "ship" to 10000))
        var called = false
        assertNull(ContextJudge.judgeCandidates(e, "this is", "shitt", null) { _, _ -> called = true; floatArrayOf() })
        assertFalse(called)
    }

    @Test fun modelKeepsVerbatimBaselineAndAbstainsWhenItWins() {
        val e = SpellEngine(listOf("hello" to 100000, "help" to 10000))
        var seen = emptyList<String>()
        val result = ContextJudge.judgeCandidates(e, "we say", "helo", "hello") { _, words ->
            seen = words
            FloatArray(words.size) { if (it == 0) 0f else -20f }
        }
        assertNull(result); assertEquals("helo", seen.first())
        assertTrue(seen.drop(1).all(SuggestionSafety::allows))
        assertNull(ContextJudge.judgeCandidates(e, "we say", "their", null) { _, words ->
            assertEquals("their", words.first()); FloatArray(words.size) { -99f }
        })
    }
    @Test fun dialectEarlyReturnChecksReplacementAndKeepsNormalConversions() {
        val e = SpellEngine(listOf("color" to 1000000, "colour" to 1000000))
        e.british = true
        assertEquals("colour", e.autocorrect("color"))
        val field = SpellEngine::class.java.getDeclaredField("usToUk").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val conversions = field.get(e) as MutableMap<String, String>
        conversions["color"] = "fuck" // hostile/future conversion data must not bypass policy
        assertNull(e.autocorrect("color"))
    }

    @Test fun blockedOriginalIsStillModelBaselineNotACensorshipTarget() {
        val e = SpellEngine(listOf("duck" to 100000, "dusk" to 10000))
        var baseline = ""
        assertNull(ContextJudge.judgeCandidates(e, "we say", "fuck", "duck") { _, words ->
            baseline = words.first()
            assertTrue(words.drop(1).all(SuggestionSafety::allows))
            FloatArray(words.size) { if (it == 0) -30f else 0f }
        })
        assertEquals("fuck", baseline)
        assertNull(e.autocorrect("fuck"))
    }

}
