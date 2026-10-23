package ai.boldkeys

import android.text.Selection
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

/** Exercises the real service editing path without loading a dictionary or a native model. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class BoldKeysServiceDoubleSpaceTest {
    private lateinit var service: BoldKeysService
    private lateinit var connection: RecordingConnection

    private class RecordingConnection : BaseInputConnection(View(RuntimeEnvironment.getApplication()), true) {
        val deletions = mutableListOf<Pair<Int, Int>>()
        val keyEvents = mutableListOf<KeyEvent>()
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            deletions += beforeLength to afterLength
            return super.deleteSurroundingText(beforeLength, afterLength)
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            keyEvents += event
            return true
        }
    }

    @Before fun setUp() {
        service = BoldKeysService()
        connection = RecordingConnection()
        // InputMethodService's active connection normally comes from the framework binding.
        ReflectionHelpers.setField(service, "mStartedInputConnection", connection)
        ReflectionHelpers.setField(service, "smart", true)
        ReflectionHelpers.setField(service, "emoticonsOn", false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
    }

    private fun text() = connection.editable.toString()
    private fun space() { service.onText(" "); ShadowSystemClock.advanceBy(Duration.ofMillis(100)) }

    @Test fun doubleSpaceOnlyReplacesTheSpaceAndNeverDamagesTheEmoji() {
        for (emoji in listOf("🙂", "❤️", "👍🏽", "👩🏾‍💻", "👨‍👩‍👧‍👦", "🇬🇧", "#️⃣")) {
            connection.editable!!.clear()
            Selection.setSelection(connection.editable, 0)
            connection.deletions.clear()
            service.onText(emoji)
            space(); space()
            assertEquals(emoji, "$emoji. ", text())
            assertEquals(emoji, listOf(1 to 0), connection.deletions)
        }
    }

    @Test fun privateAddressAndDisabledFieldsKeepTwoSpaces() {
        for (blockedField in listOf("smart", "urlLike", "doubleSpaceOn")) {
            setUp()
            ReflectionHelpers.setField(service, blockedField, blockedField == "urlLike")
            service.onText("🙂")
            space(); space()
            assertEquals(blockedField, "🙂  ", text())
            assertEquals(blockedField, emptyList<Pair<Int, Int>>(), connection.deletions)
        }
    }

    @Test fun unrelatedTypingAndDeletionCannotReuseAnEarlierSpace() {
        service.onText("🙂")
        space()
        service.onText("x")
        service.onDelete()
        space()
        assertEquals("🙂  ", text())
    }

    @Test fun deletingASpaceDisarmsTheShortcut() {
        service.onText("🙂")
        space()
        service.onDelete()
        // Even if the editor puts a space back, it is not a new first-space key press.
        connection.commitText(" ", 1)
        space()
        assertEquals("🙂  ", text())
    }

    @Test fun enterDisarmsTheShortcutEvenIfTheEditorKeepsTheSameText() {
        service.onText("🙂")
        space()
        service.onEnter()
        space()
        assertEquals("🙂  ", text())
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), connection.keyEvents.map { it.action })
        assertEquals(listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ENTER), connection.keyEvents.map { it.keyCode })
    }

    @Test fun pausesDoNotInsertAPeriod() {
        service.onText("🙂")
        space()
        ShadowSystemClock.advanceBy(Duration.ofMillis(700))
        space()
        assertEquals("🙂  ", text())
    }

    @Test fun emoticonConvertedByTheFirstSpaceCanEndASentence() {
        ReflectionHelpers.setField(service, "emoticonsOn", true)
        service.onText(":D")
        space()
        assertEquals("😀 ", text())
        space()
        assertEquals("😀. ", text())
    }
}
