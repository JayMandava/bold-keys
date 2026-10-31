package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises shipped offline tables through the real parser and multilingual normalization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class PredictionTablesTest {
    private fun engine(lang: String): SpellEngine = SpellEngine(listOf("hello" to 100), lang != "en").apply {
        loadFollowers(RuntimeEnvironment.getApplication().assets.open(Lang.pairsAsset(lang)).bufferedReader().use { it.readText() })
    }

    @Test fun shippedCorpusChoicesIgnoreContextCaseAndKeepTopFourBound() {
        for ((lang, context) in listOf("en" to "THANK", "fr" to "MERCI", "de" to "GUTEN")) {
            val e = engine(lang)
            assertTrue(e.nextWords(context, 4).isNotEmpty())
            assertEquals(e.nextWords(context.lowercase(), 4), e.nextWords(context, 4))
            assertEquals(e.nextWords(context, 4).take(2), e.nextWords(context, 2))
            assertTrue(e.nextWords(context, 20).size <= 4)
        }
    }

    @Test fun accentsUmlautsAndFrenchElisionsUseTheSameRuntimeSchema() {
        assertTrue(engine("fr").nextWords(Lang.stripElision("j'ai", "fr"), 2).isNotEmpty())
        assertTrue(engine("fr").nextWords("À", 2).isNotEmpty())
        assertTrue(engine("de").nextWords("FÜR", 2).isNotEmpty())
    }

    @Test fun unknownContextsAndSingleFollowerRowsAreSupportedOffline() {
        for (lang in listOf("en", "fr", "de")) {
            val engine = engine(lang)
            assertTrue(engine.nextWords("zznotacontextzz", 2).isEmpty())
            val row = RuntimeEnvironment.getApplication().assets.open(Lang.pairsAsset(lang)).bufferedReader().useLines { lines ->
                lines.first { !it.substringAfter('\t').contains(',') }.split('\t')
            }
            assertTrue(engine.nextWords(row[0], 2).size <= 1)
        }
    }
    @Test fun shippedNeutralContextOutputsRespectSafetyWithoutChangingTheTables() {
        for ((lang, context) in listOf("en" to "instigating", "fr" to "secteur", "de" to "namens")) {
            assertTrue("$lang/$context", engine(lang).nextWords(context, 4).all(SuggestionSafety::allows))
        }
    }

}
