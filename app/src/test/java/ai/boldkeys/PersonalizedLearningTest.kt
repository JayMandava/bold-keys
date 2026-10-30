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

/** Real service typing/Enter/undo paths and actual learned-word files, without a native model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class PersonalizedLearningTest {
    private lateinit var service: BoldKeysService
    private lateinit var connection: BaseInputConnection
    private lateinit var engine: SpellEngine
    private lateinit var learned: File

    @Before fun setUp() {
        service = Robolectric.buildService(BoldKeysService::class.java).get()
        connection = BaseInputConnection(View(RuntimeEnvironment.getApplication()), true)
        engine = SpellEngine(listOf("hello" to 1_000_000))
        learned = File(service.filesDir, "learned.tsv").apply { delete() }
        ReflectionHelpers.setField(service, "engine", engine)
        ReflectionHelpers.setField(service, "engineLang", "en")
        ReflectionHelpers.setField(service, "smart", true)
        ReflectionHelpers.setField(service, "autocorrectOn", false)
        engine.onLearnedChanged = { ReflectionHelpers.callInstanceMethod<Unit>(service, "saveLearned") }
    }

    private fun start(flags: Int = 0, type: Int = InputType.TYPE_CLASS_TEXT) {
        service.onStartInput(EditorInfo().apply { inputType = type; imeOptions = flags }, false)
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
    }

    private fun word(word: String, enter: Boolean = false) {
        connection.editable!!.clear()
        connection.commitText(word, 1)
        if (enter) service.onEnter() else service.onText(" ")
    }

    @Test fun activeEditorPolicyAlsoControlsEmojiPersonalization() {
        val keyboard = KeyboardView(service, Feedback(service), service)
        ReflectionHelpers.setField(service, "kb", keyboard)
        start(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        assertFalse(keyboard.personalizedLearningAllowed)
        start()
        assertTrue(keyboard.personalizedLearningAllowed)
        start(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(keyboard.personalizedLearningAllowed)
    }

    @Test fun ordinaryEditorLearnsAndPersistsNormally() {
        start()
        word("zorbword"); word("zorbword", enter = true)
        service.onFinishInput()
        assertEquals(2, engine.learnedSnapshot()["zorbword"])
        assertTrue(learned.readText().contains("zorbword\t2\n"))
    }

    @Test fun incognitoNeitherLearnsInMemoryNorPersistsAfterReturningToNormal() {
        start(); word("ordinaryword"); word("ordinaryword")
        val before = learned.readBytes()
        start(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        for (i in 0..3) word("privateword", enter = i % 2 == 0)
        service.onFinishInput()
        assertArrayEquals(before, learned.readBytes())
        assertFalse(engine.learnedSnapshot().containsKey("privateword"))
        start(); word("anotherword"); word("anotherword"); service.onFinishInput()
        assertFalse(learned.readText().contains("privateword"))
        assertTrue(learned.readText().contains("anotherword"))
    }

    @Test fun passwordEmailUriAndNumericPasswordDoNotLearnEvenIfSmartStateIsStale() {
        for (type in listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )) {
            start(type = type)
            word("secretword"); word("secretword", enter = true)
            service.onFinishInput()
            assertFalse("inputType=$type", engine.learnedSnapshot().containsKey("secretword"))
            assertFalse("inputType=$type", learned.exists())
        }
    }

    @Test fun webAddressDoesNotRewriteStandaloneIOrLearnIt() {
        start(type = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        ReflectionHelpers.setField(service, "urlLike", true)
        word("i")
        assertEquals("i ", connection.editable.toString())
        service.onFinishInput()
        assertFalse(learned.exists())
    }

    @Test fun incognitoUndoDoesNotTeachAnAutocorrectedWord() {
        start(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        ReflectionHelpers.setField(service, "autocorrectOn", true)
        // The existing undo path calls teach(); use a real correction to exercise that branch.
        connection.commitText("hellp", 1)
        service.onText(" ")
        assertEquals("hello ", connection.editable.toString())
        service.onDelete()
        assertEquals("hellp ", connection.editable.toString())
        service.onFinishInput()
        assertFalse(engine.learnedSnapshot().containsKey("hellp"))
        assertFalse(learned.exists())
    }
}
