package ai.boldkeys

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.io.File

/** Independent reviewer cases: private suggestion taps, repeated actions and settings clear races. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class ReviewRegressionTest {
    private lateinit var service: BoldKeysService
    private lateinit var connection: BaseInputConnection
    private lateinit var engine: SpellEngine
    private lateinit var file: File

    @Before fun setup() {
        service = Robolectric.buildService(BoldKeysService::class.java).get()
        connection = BaseInputConnection(View(RuntimeEnvironment.getApplication()), true)
        engine = SpellEngine(listOf("hello" to 1_000_000, "help" to 100_000, "world" to 50_000))
        file = File(service.filesDir, "learned.tsv").apply { delete() }
        ReflectionHelpers.setField(service, "engine", engine)
        ReflectionHelpers.setField(service, "engineLang", "en")
    }

    private fun start(type: Int = InputType.TYPE_CLASS_TEXT, flags: Int = 0, staleSmart: Boolean = true) {
        val info = EditorInfo().apply { inputType = type; imeOptions = flags }
        service.onStartInput(info, false)
        ReflectionHelpers.setField(service, "mInputEditorInfo", info)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        // Ordinary setup normally occurs in onStartInputView; private paths also test stale state.
        ReflectionHelpers.setField(service, "smart", staleSmart)
        ReflectionHelpers.setField(service, "suggestOn", true)
    }

    private fun pick(word: String) {
        ReflectionHelpers.setField(service, "shown", listOf(word))
        service.onPick(0)
    }

    @Test fun privateEditorSuggestionTapsDoNotLearnWordsOrPairsEvenWithStaleSmartState() {
        val cases = listOf(
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) to 0,
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to 0,
        )
        for ((type, flags) in cases) {
            start(type, flags)
            connection.editable!!.clear(); connection.commitText("say he", 1)
            pick("hello")
            service.onFinishInput()
            assertTrue("type=$type flags=$flags", engine.learningSnapshot().isEmpty())
            assertFalse("type=$type flags=$flags", file.exists())
        }
    }

    @Test fun realPasswordPolicySuppressesStaleSuggestionActivation() {
        start(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, staleSmart = false)
        connection.commitText("he", 1)
        pick("hello")
        assertEquals("he", connection.editable.toString())
        assertTrue(engine.learningSnapshot().isEmpty())
    }

    @Test fun selectedKnownWordAndPairAreCountedOnceForAConsumedChoice() {
        start()
        connection.commitText("say he", 1)
        pick("hello")
        val once = engine.learningSnapshot()
        assertEquals(2, once["hello"])
        assertEquals(1, once["say hello"])
        service.onPick(0) // no replacement suggestion view was rendered
        assertEquals("say hello ", connection.editable.toString())
        assertEquals(once, engine.learningSnapshot())
        service.onFinishInput()
        assertEquals(once, LearningData.parse(file.readText()))
    }

    @Test fun malformedAndOverlongSelectionsNeitherCommitNorLearn() {
        start()
        for (candidate in listOf("", "two words", "x".repeat(25), "bad\tword", "bad\nword", "fucking")) {
            connection.editable!!.clear(); connection.commitText("he", 1)
            pick(candidate)
            assertEquals(candidate, "he", connection.editable.toString())
            assertTrue(candidate, engine.learningSnapshot().isEmpty())
        }
    }

    @Test fun undoTeachingNotifiesWithTrustedCountAlreadyInstalled() {
        val e = SpellEngine(emptyList())
        val observed = ArrayList<Int?>()
        e.onLearnedChanged = { observed.add(e.learnedSnapshot()["zorbword"]) }
        e.teach("zorbword")
        assertEquals(listOf(SpellEngine.LEARN_AFTER), observed)
    }

    @Test fun frenchElisionUsesTheSamePairKeyForSelectionAndAfterSpacePrediction() {
        start()
        ReflectionHelpers.setField(service, "lang", "fr")
        connection.commitText("j'ai bi", 1)
        pick("bien")
        assertEquals(1, engine.learningSnapshot()["ai bien"])
        assertFalse(engine.learningSnapshot().containsKey("j'ai bien"))
        assertEquals(listOf("bien"), engine.nextWords("ai", 2))
    }

    @Test fun britishFollowerConversionPreservesOriginalContextRank() {
        val e = SpellEngine(listOf("color" to 1000, "colour" to 1000, "cat" to 1000))
        e.loadFollowers("paint\tcolor,cat")
        e.british = true
        assertEquals(listOf("colour", "cat"), e.nextWords("paint", 2))
        assertEquals("colour", e.suggest("co", 2, "paint").first().word)
    }

    @Test fun settingsClearRevisionDropsDirtyMemoryEvenWhenDiskWasAlreadyEmpty() {
        file.writeText("")
        ReflectionHelpers.setField(service, "learnedStamp", 31 * file.lastModified() + file.length())
        ReflectionHelpers.setField(service, "learnedRevision", LearningFiles.revision(service, "en"))
        start()
        engine.noteTyped("pendingword", "priorword", selected = true)
        assertTrue(engine.learningSnapshot().containsKey("priorword pendingword"))
        // Settings clears an already empty on-disk file while a delayed save is pending.
        file.writeText("")
        LearningFiles.edited(service, "en")
        service.onFinishInput()
        assertTrue(engine.learningSnapshot().isEmpty())
        assertTrue(LearningData.parse(file.readText()).isEmpty())
    }

    @Test fun clearingWithSameMillisecondTimestampCannotResurrectOldWordsOrPairs() {
        val old = linkedMapOf("oldword" to 4, "oldword oldpair" to 3)
        file.writeText(LearningData.encode(old))
        val stamp = file.lastModified()
        engine.loadLearned(old)
        ReflectionHelpers.setField(service, "learnedStamp", 31 * stamp + file.length())
        start()
        // Filesystem millisecond timestamps can coincide for two atomic writes.
        file.writeText("")
        assertTrue(file.setLastModified(stamp))
        connection.commitText("hello", 1)
        service.onText(" ")
        service.onFinishInput()
        val saved = LearningData.parse(file.readText())
        assertFalse(saved.containsKey("oldword"))
        assertFalse(saved.containsKey("oldword oldpair"))
        assertEquals(1, saved["hello"])
    }
}
