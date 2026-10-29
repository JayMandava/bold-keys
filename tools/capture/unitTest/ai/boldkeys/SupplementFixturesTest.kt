package ai.boldkeys

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Bundled dictionaries, not synthetic candidates: avoid spending a run on a guessed phrase. */
class SupplementFixturesTest {
    private fun engine(lang:String):SpellEngine {
        val assets=File("src/main/assets")
        return SpellEngine.fromTsv(File(assets,Lang.wordsAsset(lang)).readText(),Lang.hasAccents(lang)).apply {
            setLayout(if(lang=="de") "qwertz" else if(lang=="fr") "azerty" else "qwerty")
            loadFollowers(File(assets,Lang.pairsAsset(lang)).readText())
        }
    }
    @Test fun actualFrenchAndGermanCorrectionFixtures() {
        assertEquals("été",engine("fr").autocorrect("ete"))
        assertEquals("für",engine("de").autocorrect("fur"))
    }
    @Test fun nextWordAndBothEnglishSpellings() {
        val e=engine("en")
        assertEquals("you",e.nextWords("thank",2).first())
        e.british=true;assertEquals("colour",e.autocorrect("color"))
        e.british=false;assertEquals("color",e.autocorrect("colour"))
    }
    @Test fun automaticLearningAndCompletionUseRealDictionary() {
        val e=engine("en")
        assertFalse(e.isWord("zorbital"));e.noteTyped("zorbital");assertFalse(e.isWord("zorbital"))
        e.noteTyped("zorbital");assertTrue(e.isWord("zorbital"))
        assertTrue(e.suggest("hel",2).isNotEmpty())
    }
    @Test fun actualLayoutsAndEmoticonFixture() {
        assertEquals("Z",Layouts.rows("qwertz")[0][5].label)
        assertEquals("A",Layouts.rows("azerty")[0][0].label)
        assertEquals("🙂",Layouts.emoticonsNow[":)"])
        assertEquals(":",secondaryFor(1,5))
        assertEquals(")",secondaryFor(0,5))
    }
}
