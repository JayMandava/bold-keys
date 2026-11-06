package ai.boldkeys

import android.Manifest
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoiceSafetyTest {
    private fun service(): BoldKeysService = Robolectric.buildService(BoldKeysService::class.java).get().also {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("bk", 0).edit().clear().apply()
        VoiceModel.file(app).delete()
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        it.onCreateInputView()
    }
    @After fun cleanup() { VoiceModel.file(RuntimeEnvironment.getApplication()).delete() }
    private fun field(s: BoldKeysService, name: String, value: Any?) = ReflectionHelpers.setField(s, name, value)
    private fun policy(s: BoldKeysService, type: Int) {
        val info = EditorInfo().apply { inputType = type }
        s.javaClass.getDeclaredMethod("applyLearningPolicy", EditorInfo::class.java).apply { isAccessible = true }.invoke(s, info)
    }
    private fun phase(s: BoldKeysService) = ReflectionHelpers.getField<KeyboardView.VoiceState>(s, "voicePhase")
    private fun ready(s: BoldKeysService) {
        Prefs.put(s, "voiceTyping", true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // UI eligibility fixture only; native inference independently verifies the complete model hash.
        VoiceModel.file(s).writeBytes(byteArrayOf(1))
        policy(s, InputType.TYPE_CLASS_TEXT)
    }
    private fun pending(s: BoldKeysService, ic: InputConnection): Int {
        ready(s); field(s, "mStartedInputConnection", ic)
        field(s, "voiceAnchor", VoiceEditorAnchor.capture(ic))
        field(s, "voiceEpoch", 7); field(s, "voicePhase", KeyboardView.VoiceState.TRANSCRIBING)
        return 7
    }
    private fun complete(s: BoldKeysService, epoch: Int, ic: InputConnection, text: String?, error: String? = null) {
        s.javaClass.getDeclaredMethod("completeVoice", Int::class.javaPrimitiveType, InputConnection::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }.invoke(s, epoch, ic, text, error)
    }
    private fun connection(s: BoldKeysService) = BaseInputConnection(View(s), true)

    @Test fun offByDefaultAndMissingPermissionOrModelCannotStart() {
        val s = service(); policy(s, InputType.TYPE_CLASS_TEXT)
        assertFalse(Prefs.voiceTyping(s)); assertFalse(s.onVoiceStart())
        Prefs.put(s, "voiceTyping", true); assertFalse(s.onVoiceStart())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(s.onVoiceStart())
    }
    @Test fun passwordAndNonTextFieldsDisableVoiceWithoutAddingAnyControls() {
        val s = service(); ready(s)
        val root = ReflectionHelpers.getField<android.widget.LinearLayout>(s, "root")
        assertEquals(2, root.childCount)
        assertTrue(root.getChildAt(0) is SuggestionBar); assertTrue(root.getChildAt(1) is KeyboardView)
        for (type in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_CLASS_PHONE)) {
            policy(s, type); assertFalse(root.getChildAt(1).let { it as KeyboardView }.voiceEnabled)
            assertFalse(s.onVoiceStart()); assertEquals(2, root.childCount)
        }
        policy(s, InputType.TYPE_CLASS_TEXT); assertTrue((root.getChildAt(1) as KeyboardView).voiceEnabled)
        assertEquals(2, root.childCount)
    }
    @Test fun completionAutomaticallyCommitsLiteralTextOnceWithoutSendOrAutocorrection() {
        val s = service(); var actions = 0
        val ic = object : BaseInputConnection(View(s), true) {
            override fun performEditorAction(actionCode: Int): Boolean { actions++; return true }
        }
        val epoch = pending(s, ic)
        complete(s, epoch, ic, "teh café 🙂\nNext")
        complete(s, epoch, ic, "duplicate")
        assertEquals("teh café 🙂\nNext", ic.editable.toString()); assertEquals(0, actions)
        assertEquals(KeyboardView.VoiceState.IDLE, phase(s))
    }
    @Test fun releaseShowsOnlyTranscribingStateUntilTheResultArrives() {
        val s = service(); val ic = connection(s); val epoch = pending(s, ic)
        field(s, "voicePhase", KeyboardView.VoiceState.RECORDING)
        s.onVoiceStop(); s.onVoiceStop()
        assertEquals(KeyboardView.VoiceState.TRANSCRIBING, phase(s)); assertEquals("", ic.editable.toString())
        val root = ReflectionHelpers.getField<android.widget.LinearLayout>(s, "root")
        assertEquals(2, root.childCount)
        assertEquals(KeyboardView.VoiceState.TRANSCRIBING, (root.getChildAt(1) as KeyboardView).voiceState)
        complete(s, epoch, ic, "recognized")
        assertEquals("recognized", ic.editable.toString())
    }
    @Test fun cancellationAndDismissalRejectDelayedResults() {
        val s = service(); val ic = connection(s)
        var epoch = pending(s, ic); s.onVoiceCancel(); complete(s, epoch, ic, "canceled")
        epoch = pending(s, ic); s.onWindowHidden(); complete(s, epoch, ic, "hidden")
        epoch = pending(s, ic); s.onFinishInputView(false); complete(s, epoch, ic, "finished")
        assertEquals("", ic.editable.toString()); assertEquals(KeyboardView.VoiceState.IDLE, phase(s))
    }
    @Test fun typingDiscardsPendingVoiceAndStillTypesNormally() {
        val s = service(); val ic = connection(s); val epoch = pending(s, ic)
        s.onText("x"); complete(s, epoch, ic, "late speech")
        assertEquals("x", ic.editable.toString())
    }
    @Test fun cursorAndEditorChangesRejectDelayedResults() {
        val s = service(); val first = connection(s); val other = connection(s)
        var epoch = pending(s, first); s.onUpdateSelection(0, 0, 1, 1, -1, -1)
        complete(s, epoch, first, "cursor changed")
        epoch = pending(s, first); field(s, "mStartedInputConnection", other)
        complete(s, epoch, first, "other editor")
        epoch = pending(s, first); s.onStartInput(EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }, false)
        complete(s, epoch, first, "restarted")
        assertEquals("", first.editable.toString()); assertEquals("", other.editable.toString())
    }
    @Test fun changedSurroundingTextPasswordOrRevokedPermissionCannotReceiveSpeech() {
        val s = service(); val ic = connection(s)
        var epoch = pending(s, ic); ic.commitText("new text", 1); complete(s, epoch, ic, "stale")
        assertEquals("new text", ic.editable.toString())
        epoch = pending(s, ic); policy(s, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        complete(s, epoch, ic, "password")
        epoch = pending(s, ic); shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        complete(s, epoch, ic, "permission revoked")
        assertEquals("new text", ic.editable.toString())
    }
    @Test fun errorsAndEmptyResultsNeverInsertText() {
        val s = service(); val ic = connection(s)
        var epoch = pending(s, ic); complete(s, epoch, ic, "discard", "Recognition failed")
        epoch = pending(s, ic); complete(s, epoch, ic, "  ")
        assertEquals("", ic.editable.toString()); assertEquals(KeyboardView.VoiceState.IDLE, phase(s))
    }
    @Test fun optingOutDiscardsPendingVoiceImmediately() {
        val s = service(); val ic = connection(s); val epoch = pending(s, ic)
        val listener = ReflectionHelpers.getField<android.content.SharedPreferences.OnSharedPreferenceChangeListener>(s, "voicePreferenceListener")
        Prefs.put(s, "voiceTyping", false)
        listener.onSharedPreferenceChanged(s.getSharedPreferences("bk", 0), "voiceTyping")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        complete(s, epoch, ic, "discard")
        assertEquals("", ic.editable.toString()); assertEquals(KeyboardView.VoiceState.IDLE, phase(s))
    }
    @Test fun corruptModelIsRejected() {
        val file = VoiceModel.file(RuntimeEnvironment.getApplication()); file.writeBytes(ByteArray(32_152_673))
        assertFalse(VoiceModel.valid(file))
    }
}
