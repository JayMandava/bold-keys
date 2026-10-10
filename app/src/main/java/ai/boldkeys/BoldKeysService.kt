package ai.boldkeys

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout

/** The keyboard service: turns presses from [KeyboardView] into text in whatever app is typing. */
class BoldKeysService : InputMethodService(), KeyboardView.Listener {
    private lateinit var kb: KeyboardView

    override fun onCreateInputView(): View {
        kb = KeyboardView(this, Feedback(this), this)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF08090a.toInt())
            addView(kb, LinearLayout.LayoutParams(-1, -2))
            // The keyboard is drawn edge to edge: keep the keys clear of the system's navigation bar, which has its own keyboard-switch button.
            setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(bars.left, 0, bars.right, bars.bottom)
                insets
            }
        }
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshAutoCaps()
    }

    override fun onText(text: String) {
        currentInputConnection?.commitText(text, 1)
        refreshAutoCaps()
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
        refreshAutoCaps()
    }

    override fun onEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        refreshAutoCaps()
    }

    private fun refreshAutoCaps() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        kb.setAutoShift(ic.getCursorCapsMode(info.inputType) != 0)
    }
}
