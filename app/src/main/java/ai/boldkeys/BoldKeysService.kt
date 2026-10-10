package ai.boldkeys

import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import java.io.File
import java.util.concurrent.Executors

/** The keyboard service: turns presses from [KeyboardView] into text, and runs the suggestions, autocorrect and trackpad. */
class BoldKeysService : InputMethodService(), KeyboardView.Listener, SuggestionBar.Listener {
    private lateinit var kb: KeyboardView
    private lateinit var bar: SuggestionBar
    private lateinit var feedback: Feedback

    private val ui = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var engine: SpellEngine? = null
    private var autocorrectOn = true
    private var suggestOn = true
    private var emoticonsOn = true
    private var autoCapsOn = true
    private var learnedStamp = 0L
    private var smart = false // suggestions and autocorrect are off in password, email, web-address and "no suggestions" fields
    private var shown: List<String> = emptyList()

    /** The last autocorrect, kept so Backspace right after it can put back what was typed. */
    private class Undo(val typed: String, val fixed: String, val sep: String)
    private var undo: Undo? = null

    override fun onCreate() {
        super.onCreate()
        worker.execute {
            val e = SpellEngine.fromTsv(assets.open("words.tsv").bufferedReader().use { it.readText() })
            e.loadLearned(readLearned())
            e.onLearnedChanged = { saveLearned() }
            engine = e
            ui.post { refreshStrip() }
        }
    }

    override fun onDestroy() { saveLearned(); super.onDestroy() }

    // ---- closing when the user taps outside the keyboard

    private val hideRunnable = Runnable { requestHideSelf(0) }
    private var watchingOutside = false

    /**
     * Asks Android to report touches that land outside the keyboard (the touch still goes on to the app underneath). A tap in the text field
     * you are typing in moves the cursor or restarts input, and that cancels the hide, so tapping back into the field keeps the keyboard up.
     */
    private fun watchOutsideTouches() {
        if (watchingOutside) return
        val w = window?.window ?: return
        w.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        val inner = w.callback
        w.callback = object : Window.Callback by inner {
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    val field = editorOnScreen
                    val slop = 12 * resources.displayMetrics.density // the field's position lags a few pixels while the page shifts
                    val inField = field != null && event.rawX in field.left - slop..field.right + slop && event.rawY in field.top - slop..field.bottom + slop
                    ui.removeCallbacks(hideRunnable)
                    if (!inField) ui.postDelayed(hideRunnable, 330) // a tap in the text field you are typing in keeps the keyboard up
                }
                return inner.dispatchTouchEvent(event)
            }
        }
        watchingOutside = true
    }

    /** Tapping the text field you are already typing in makes the app ask for the keyboard: that cancels a pending close, even if the cursor does not move. */
    override fun onShowInputRequested(flags: Int, configChange: Boolean): Boolean {
        ui.removeCallbacks(hideRunnable)
        return super.onShowInputRequested(flags, configChange)
    }

    override fun onCreateInputView(): View {
        watchOutsideTouches()
        feedback = Feedback(this)
        kb = KeyboardView(this, feedback, this)
        bar = SuggestionBar(this, feedback, this) { kb.isShiftHeld() }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0a0a0b.toInt())
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(kb, LinearLayout.LayoutParams(-1, -2))
            // The keyboard is drawn edge to edge: keep the keys clear of the system's navigation bar.
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
        ui.removeCallbacks(hideRunnable)
        // what the user chose in Settings, read afresh each time the keyboard opens
        autocorrectOn = Prefs.autocorrect(this); suggestOn = Prefs.suggestions(this); emoticonsOn = Prefs.emoticons(this); autoCapsOn = Prefs.autoCapitals(this)
        kb.applySettings(); bar.applyTheme(Prefs.isDark(this))
        reloadLearnedIfChanged()
        currentInputConnection?.requestCursorUpdates(android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR or android.view.inputmethod.InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        smart = cls == InputType.TYPE_CLASS_TEXT &&
            info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0 &&
            variation !in PRIVATE_VARIATIONS
        undo = null
        refreshAutoCaps()
        refreshStrip()
    }

    override fun onFinishInput() { super.onFinishInput(); saveLearned() }

    /** Where the text field being typed in is on the screen (kept up to date by the system), so a tap in it is not mistaken for a tap away. */
    private var editorOnScreen: android.graphics.RectF? = null

    override fun onUpdateCursorAnchorInfo(info: android.view.inputmethod.CursorAnchorInfo) {
        val b = info.editorBoundsInfo?.editorBounds
        editorOnScreen = b?.let { android.graphics.RectF(it).also { r -> info.matrix.mapRect(r) } }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        ui.removeCallbacks(hideRunnable) // the cursor moved: the tap was in the field, not away from it
        if (::bar.isInitialized) refreshStrip()
    }

    // ---- typing

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val e = engine
        if (smart && emoticonsOn && text.length == 1 && convertEmoticon(ic, text)) return
        if (smart && e != null && text.length == 1 && text[0] in SEPARATORS) {
            val word = currentWord()
            if (word.isNotEmpty()) {
                val fix = if (word == "i") "I" else if (autocorrectOn) e.autocorrect(word)?.let { matchCase(word, it) } else null
                if (fix != null && fix != word) {
                    ic.deleteSurroundingText(word.length, 0)
                    ic.commitText(fix + text, 1)
                    undo = Undo(word, fix, text)
                    refreshAutoCaps(); refreshStrip()
                    return
                }
                e.noteTyped(word)
            }
        }
        undo = null
        ic.commitText(text, 1)
        refreshAutoCaps()
        refreshStrip()
    }

    /** Turns a text emoticon just completed by [typed] into its emoji; the undo list remembers it so Backspace gives the text back. */
    private fun convertEmoticon(ic: android.view.inputmethod.InputConnection, typed: String): Boolean {
        val before = ic.getTextBeforeCursor(6, 0)?.toString() ?: return false
        fun boundary(len: Int, k: String) = len == k.length || before[len - k.length - 1].isWhitespace()
        val now = before + typed
        for ((k, emoji) in Layouts.emoticonsNow) if (now.endsWith(k) && boundary(now.length, k)) {
            ic.deleteSurroundingText(k.length - 1, 0)
            ic.commitText(emoji, 1)
            undo = Undo(k, emoji, "")
            refreshAutoCaps(); refreshStrip()
            return true
        }
        if (typed.length == 1 && typed[0] in SEPARATORS) {
            for ((k, emoji) in Layouts.emoticonsOnSpace) if (before.endsWith(k) && boundary(before.length, k)) {
                ic.deleteSurroundingText(k.length, 0)
                ic.commitText(emoji + typed, 1)
                undo = Undo(k, emoji, typed)
                refreshAutoCaps(); refreshStrip()
                return true
            }
        }
        return false
    }

    override fun onDelete() {
        val ic = currentInputConnection ?: return
        val u = undo
        if (u != null) {
            val tail = u.fixed + u.sep
            if (ic.getTextBeforeCursor(tail.length, 0)?.toString() == tail) {
                // take the correction back: what was typed returns, and the word is learned so it is not "fixed" again
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(u.typed + u.sep, 1)
                if (u.typed.all { it.isLetter() || it == '\'' }) engine?.teach(u.typed)
                undo = null
                refreshAutoCaps(); refreshStrip()
                return
            }
        }
        undo = null
        if (ic.getSelectedText(0).isNullOrEmpty()) ic.deleteSurroundingText(1, 0) else ic.commitText("", 1)
        refreshAutoCaps()
        refreshStrip()
    }

    override fun onEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noAction = info != null && info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        val word = currentWord()
        if (smart && word.isNotEmpty()) engine?.noteTyped(word)
        undo = null
        if (!noAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
        refreshAutoCaps()
        refreshStrip()
    }

    // ---- suggestions

    /** The letters just before the cursor, if the cursor is at the end of a word. */
    private fun currentWord(): String {
        val ic = currentInputConnection ?: return ""
        val before = ic.getTextBeforeCursor(40, 0)?.toString() ?: return ""
        var i = before.length
        while (i > 0 && (before[i - 1].isLetter() || before[i - 1] == '\'')) i--
        var w = before.substring(i)
        w = w.trimStart('\'')
        return w
    }

    private fun refreshStrip() {
        if (!::bar.isInitialized) return
        val e = engine
        val ic = currentInputConnection
        val word = if (smart && e != null && ic != null && ic.getSelectedText(0).isNullOrEmpty()) currentWord() else ""
        val out = if (word.isEmpty() || e == null || !suggestOn) emptyList() else e.suggest(word, 2).map { matchCase(word, it.word) }
        shown = out
        bar.setSuggestions(out)
    }

    override fun onPick(index: Int) {
        val ic = currentInputConnection ?: return
        val pick = shown.getOrNull(index) ?: return
        val word = currentWord()
        if (word.isEmpty()) return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(word.length, 0)
        ic.commitText("$pick ", 1)
        ic.endBatchEdit()
        undo = null
        refreshAutoCaps(); refreshStrip()
    }

    /** Gives [cand] the capitals [typed] had: ALL CAPS stays all caps, a first capital stays a first capital. */
    private fun matchCase(typed: String, cand: String): String = when {
        typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> cand.uppercase()
        typed.isNotEmpty() && typed[0].isUpperCase() -> cand.replaceFirstChar { it.uppercase() }
        else -> cand
    }

    // ---- trackpad

    override fun onMove(dx: Int, dy: Int, select: Boolean) {
        val meta = if (select) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        fun press(code: Int, times: Int) {
            repeat(kotlin.math.abs(times)) {
                val t = SystemClock.uptimeMillis()
                currentInputConnection?.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, meta))
                currentInputConnection?.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0, meta))
            }
        }
        if (dx != 0) press(if (dx > 0) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT, dx)
        if (dy != 0) press(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP, dy)
        undo = null
    }

    private fun refreshAutoCaps() {
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo ?: return
        if (info.inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return
        kb.setAutoShift(autoCapsOn && ic.getCursorCapsMode(info.inputType) != 0)
    }

    // ---- what the keyboard has learned (words typed twice that the dictionary did not know), kept on the phone only

    private fun learnedFile() = File(filesDir, "learned.tsv")

    private fun readLearned(): Map<String, Int> = runCatching {
        learnedFile().takeIf { it.exists() }?.readLines()?.mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0] to (it[1].toIntOrNull() ?: 0) } }?.toMap()
    }.getOrNull() ?: emptyMap()

    /** The personal dictionary can be edited in Settings: pick up those changes the next time the keyboard opens. */
    private fun reloadLearnedIfChanged() {
        val f = learnedFile(); val stamp = if (f.exists()) f.lastModified() else 0L
        if (stamp != learnedStamp) { learnedStamp = stamp; engine?.loadLearned(readLearned()) }
    }

    @Synchronized private fun saveLearned() {
        val e = engine ?: return
        reloadLearnedIfChanged() // never overwrite words that were added or removed in Settings
        runCatching { learnedFile().writeText(e.learnedSnapshot().entries.joinToString("") { "${it.key}\t${it.value}\n" }); learnedStamp = learnedFile().lastModified() }
    }

    private companion object {
        const val SEPARATORS = " .,!?;:"
        val PRIVATE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
    }
}
