package ai.boldkeys

import android.Manifest
import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
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
    private fun field(s: BoldKeysService, name: String, value: Any?) = ReflectionHelpers.setField(s, name, value)
    private fun policy(s: BoldKeysService, type: Int) {
        val info = EditorInfo().apply { inputType = type }
        s.javaClass.getDeclaredMethod("applyLearningPolicy", EditorInfo::class.java).apply { isAccessible = true }.invoke(s, info)
    }
    @Test fun offByDefaultAndMissingPermissionOrModelCannotStart() {
        val s = service()
        policy(s, InputType.TYPE_CLASS_TEXT)
        assertFalse(Prefs.voiceTyping(s)); assertFalse(s.onVoiceStart())
        Prefs.put(s, "voiceTyping", true)
        assertFalse(s.onVoiceStart())
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertFalse(s.onVoiceStart())
    }
    @Test fun everyPasswordVariationAndNonTextFieldDisablesVoice() {
        val s = service()
        for (type in listOf(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, InputType.TYPE_CLASS_PHONE)) {
            policy(s, type)
            assertEquals(false, ReflectionHelpers.getField<Boolean>(s, "voiceEditor"))
        }
        policy(s, InputType.TYPE_CLASS_TEXT)
        assertEquals(true, ReflectionHelpers.getField<Boolean>(s, "voiceEditor"))
    }
    @Test fun insertCommitsLiteralTranscriptWithoutSendOrAutocorrection() {
        val s = service()
        var actions = 0
        val ic = object : BaseInputConnection(View(s), true) {
            override fun performEditorAction(actionCode: Int): Boolean { actions++; return true }
        }
        field(s, "mStartedInputConnection", ic)
        policy(s, InputType.TYPE_CLASS_TEXT)
        Prefs.put(s, "voiceTyping", true)
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)
        // Presence is sufficient for the UI gate; real inference separately checks the entire hash.
        VoiceModel.file(s).writeBytes(byteArrayOf(1))
        field(s, "voiceTranscript", "teh café 🙂\nNext")
        field(s, "voiceAnchor", VoiceEditorAnchor.capture(ic))
        s.javaClass.getDeclaredMethod("insertVoice").apply { isAccessible = true }.invoke(s)
        assertEquals("teh café 🙂\nNext", ic.editable.toString()); assertEquals(0, actions)
        assertNull(ReflectionHelpers.getField<String?>(s, "voiceTranscript"))
        VoiceModel.file(s).delete()
    }
    @Test fun cursorMoveAndLifecycleDiscardPendingTranscript() {
        val s = service()
        field(s, "voiceTranscript", "old editor")
        s.onUpdateSelection(1, 1, 2, 2, -1, -1)
        assertNull(ReflectionHelpers.getField<String?>(s, "voiceTranscript"))
        field(s, "voiceTranscript", "hidden")
        s.onWindowHidden()
        assertNull(ReflectionHelpers.getField<String?>(s, "voiceTranscript"))
    }
    @Test fun corruptModelIsRejected() {
        val app = RuntimeEnvironment.getApplication()
        val file = VoiceModel.file(app)
        file.writeBytes(ByteArray(32_152_673))
        assertFalse(VoiceModel.valid(file)); file.delete()
    }

    @Test fun delayedOutputCannotCrossConnectionOrSurroundingTextChanges() {
        val app = RuntimeEnvironment.getApplication()
        val first = BaseInputConnection(View(app), true)
        val other = BaseInputConnection(View(app), true)
        first.commitText("hello", 1)
        val anchor = VoiceEditorAnchor.capture(first)!!
        assertTrue(anchor.matches(first)); assertFalse(anchor.matches(other))
        first.commitText("new text", 1)
        assertFalse(anchor.matches(first))
    }

    @Test fun optingOutDiscardsPendingVoiceImmediately() {
        val s = service()
        field(s, "voiceTranscript", "discard this")
        val listener = ReflectionHelpers.getField<android.content.SharedPreferences.OnSharedPreferenceChangeListener>(s, "voicePreferenceListener")
        Prefs.put(s, "voiceTyping", false)
        listener.onSharedPreferenceChanged(s.getSharedPreferences("bk", 0), "voiceTyping")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNull(ReflectionHelpers.getField<String?>(s, "voiceTranscript"))
    }
}
