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
    private var doubleSpaceOn = true
    private var nextWordOn = true
    private var lastSpaceAt = 0L
    private var learnedStamp = 0L
    private var urlLike = false // a web-address field: suggestions only, nothing is changed for the user
    private var smart = false // suggestions and autocorrect are off in password, email, web-address and "no suggestions" fields
    private var shown: List<String> = emptyList()

    // The context model (optional): judges the word just typed by the sentence around it, a moment after the space is typed.
    private val lmWorker = Executors.newSingleThreadExecutor()
    @Volatile private var lm: ContextLm? = null
    @Volatile private var lmId: String? = null
    private var contextOn = false
    private var lmJob = 0

    /** The last autocorrect, kept so Backspace right after it can put back what was typed. */
    private class Undo(val typed: String, val fixed: String, val sep: String)
    private var undo: Undo? = null

    override fun onCreate() {
        super.onCreate()
        worker.execute {
            val e = SpellEngine.fromTsv(assets.open("words.tsv").bufferedReader().use { it.readText() })
            e.loadLearned(readLearned())
            runCatching { e.loadFollowers(assets.open("pairs.tsv").bufferedReader().use { it.readText() }) }
            e.onLearnedChanged = { saveLearned() }
            e.setLayout(Prefs.layout(this))
            engine = e
            ui.post { refreshStrip() }
        }
    }

    override fun onDestroy() { saveLearned(); lmWorker.execute { lm?.close(); lm = null }; super.onDestroy() }

    /** Loads the chosen model when the setting is on (and one is installed), and lets go of it when it is off or changed. */
    private fun syncContextModel() {
        ContextModels.init(this)
        val want = if (contextOn) ContextModels.selected() else null
        if (want?.id == lmId) return
        lmId = want?.id
        lmWorker.execute {
            lm?.close(); lm = null
            if (want != null) lm = ContextLm.load(ContextModels.file(want).absolutePath)
        }
    }

    /**
     * After a space or punctuation mark has been typed, asks the model whether the word before it was the right one. This runs behind the typing
     * (the space is already in): if the model disagrees and nothing has been typed since, the word is swapped in place and Backspace undoes it.
     */
    private fun judgeInContext(word: String, fixed: String?, sep: String, before: String) {
        val e = engine ?: return
        if (!contextOn || lm == null || !Autofix.eligible(word, before) || !word.all { it.isLetter() || it == '\'' }) return
        val left = ContextJudge.leftContext(before) ?: return
        val shownWord = fixed ?: word
        val job = ++lmJob
        lmWorker.execute {
            val model = lm ?: return@execute
            if (job != lmJob) return@execute // a newer word has been typed: this one is stale
            val pick = ContextJudge.judge(model, e, left, word, fixed, sep != " ") ?: return@execute
            ui.post {
                val ic = currentInputConnection ?: return@post
                val tail = shownWord + sep
                if (job != lmJob || ic.getTextBeforeCursor(tail.length, 0)?.toString() != tail) return@post
                val out = matchCase(word, pick)
                ic.beginBatchEdit()
                ic.deleteSurroundingText(tail.length, 0)
                ic.commitText(out + sep, 1)
                ic.endBatchEdit()
                undo = Undo(word, out, sep)
                refreshAutoCaps(); refreshStrip()
            }
        }
    }

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
            root = this
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            addView(bar, LinearLayout.LayoutParams(-1, -2))
            addView(kb, LinearLayout.LayoutParams(-1, -2))
            // The keyboard is drawn edge to edge: keep the keys clear of the system's navigation bar.
            setOnApplyWindowInsetsListener { v, insets -> applyBarInsets(v, insets); insets }
        }
    }

    private var root: View? = null

    private fun applyBarInsets(v: View, insets: android.view.WindowInsets) {
        val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
        // any of these can be the one that carries the bar's height, depending on the navigation mode and when the window was shown
        val bottom = maxOf(bars.bottom, insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom, insets.getInsets(android.view.WindowInsets.Type.tappableElement()).bottom)
        if (v.paddingBottom != bottom || v.paddingLeft != bars.left || v.paddingRight != bars.right) v.setPadding(bars.left, 0, bars.right, bottom)
    }

    /** The window can be shown before its insets arrive (or with stale ones), which left the bottom row under the navigation bar until the keyboard was switched. Ask again, and read them directly. */
    private fun refreshInsets() {
        val v = root ?: return
        v.requestApplyInsets()
        v.rootWindowInsets?.let { applyBarInsets(v, it) }
    }

    override fun onWindowShown() { super.onWindowShown(); refreshInsets(); root?.postDelayed({ refreshInsets() }, 250) }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        ui.removeCallbacks(hideRunnable)
        // what the user chose in Settings, read afresh each time the keyboard opens
        autocorrectOn = Prefs.autocorrect(this); suggestOn = Prefs.suggestions(this); emoticonsOn = Prefs.emoticons(this); autoCapsOn = Prefs.autoCapitals(this)
        doubleSpaceOn = Prefs.doubleSpace(this); nextWordOn = Prefs.nextWord(this); engine?.british = Prefs.spelling(this) == "uk"; engine?.setLayout(Prefs.layout(this))
        contextOn = Prefs.contextModel(this); syncContextModel()
        kb.applySettings(); bar.applyTheme(Prefs.isDark(this))
        reloadLearnedIfChanged()
        currentInputConnection?.requestCursorUpdates(android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR or android.view.inputmethod.InputConnection.CURSOR_UPDATE_FILTER_EDITOR_BOUNDS)
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        // search boxes (e.g. NewPipe) often set "no suggestions" + "filter" because the app shows its own; the user still wants help typing there
        val search = info.imeOptions and EditorInfo.IME_MASK_ACTION == EditorInfo.IME_ACTION_SEARCH
        // address bars (Brave, Chrome) double as search boxes: they get the suggestion strip but are never auto-corrected or rewritten
        urlLike = variation == InputType.TYPE_TEXT_VARIATION_URI
        smart = cls == InputType.TYPE_CLASS_TEXT &&
            (search || urlLike || info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS == 0) &&
            variation !in (if (search || urlLike) SECRET_VARIATIONS else PRIVATE_VARIATIONS)
        undo = null
        refreshAutoCaps()
        refreshStrip()
        refreshInsets()
    }

    override fun onFinishInput() { super.onFinishInput(); saveLearned() }

    /** Where the text field being typed in is on the screen (kept up to date by the system), so a tap in it is not mistaken for a tap away. */
    private var editorOnScreen: android.graphics.RectF? = null

    override fun onUpdateCursorAnchorInfo(info: android.view.inputmethod.CursorAnchorInfo) {
        val b = info.editorBoundsInfo?.editorBounds
        editorOnScreen = b?.let { android.graphics.RectF(it).also { r -> info.matrix.mapRect(r) } }
    }

    private var selStart = 0
    private var selEnd = 0
    private var dragAnchor = -1 // where a trackpad selection started; -1 when none is being dragged
    private var dragFocus = 0

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart; selEnd = newSelEnd
        ui.removeCallbacks(hideRunnable) // the cursor moved: the tap was in the field, not away from it
        if (::bar.isInitialized) refreshStrip()
    }

    // ---- typing

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val e = engine
        if (smart && !urlLike && doubleSpaceOn && text == " ") {
            // two spaces in quick succession after a word end the sentence: ". " in place of the first space, as on the original
            val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
            val now = SystemClock.uptimeMillis()
            if (before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in ")\"'") && now - lastSpaceAt < 700) {
                ic.deleteSurroundingText(1, 0)
                ic.commitText(". ", 1)
                lastSpaceAt = 0; undo = null
                refreshAutoCaps(); refreshStrip()
                return
            }
            lastSpaceAt = now
        }
        if (smart && !urlLike && emoticonsOn && text.length == 1 && convertEmoticon(ic, text)) return
        if (smart && e != null && text.length == 1 && text[0] in SEPARATORS) {
            val word = currentWord()
            if (word.isNotEmpty()) {
                val ctx = ic.getTextBeforeCursor(160, 0)?.toString()?.dropLast(word.length) ?: ""
                val fix = if (word == "i" && !ctx.lastOrNull().let { it != null && (it.isLetterOrDigit() || it in "@#/._-") }) "I" else if (autocorrectOn && !urlLike) Autofix.fix(e, word, ctx)?.let { matchCase(word, it) } else null
                if (fix != null && fix != word) {
                    ic.deleteSurroundingText(word.length, 0)
                    ic.commitText(fix + text, 1)
                    undo = Undo(word, fix, text)
                    refreshAutoCaps(); refreshStrip()
                    if (contextOn && lm != null) judgeInContext(word, fix, text, ctx)
                    return
                }
                e.noteTyped(word)
                if (contextOn && lm != null && !urlLike) { ic.commitText(text, 1); undo = null; refreshAutoCaps(); refreshStrip(); judgeInContext(word, null, text, ctx); return }
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

    override fun onShortcut(letter: Char): Boolean {
        val sc = Shortcuts.get(this, letter) ?: return false
        val typed = runCatching { Shortcuts.run(this, sc) }.getOrNull()
        if (typed != null) onText(typed)
        return true
    }

    /** A letter was held and an accented form chosen: it replaces the plain letter that was typed when the key went down. */
    override fun onAccent(accent: String) {
        val ic = currentInputConnection ?: return
        undo = null
        ic.deleteSurroundingText(1, 0)
        ic.commitText(accent, 1)
        refreshAutoCaps()
        refreshStrip()
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
        val out = when {
            e == null || !suggestOn || !smart || ic == null -> emptyList()
            word.isNotEmpty() -> e.suggest(word, 2).map { matchCase(word, it.word) }
            nextWordOn && ic.getSelectedText(0).isNullOrEmpty() -> nextWordSuggestions(e, ic)
            else -> emptyList()
        }
        shown = out
        bar.setSuggestions(out)
    }

    /** With no word being typed: the words that most often follow the one just finished, or the usual ways to start a sentence. */
    private fun nextWordSuggestions(e: SpellEngine, ic: android.view.inputmethod.InputConnection): List<String> {
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return emptyList()
        if (before.isNotEmpty() && !before.last().isWhitespace()) return emptyList() // right after punctuation or a symbol: nothing sensible to offer
        val trimmed = before.trimEnd()
        val sentenceStart = trimmed.isEmpty() || trimmed.last() in ".!?" || before.endsWith("\n")
        val list = if (sentenceStart) listOf("I", "The") else {
            val prev = Regex("[A-Za-z']+$").find(trimmed)?.value ?: return emptyList()
            e.nextWords(prev, 2)
        }
        return list.map { w -> if (sentenceStart) w.replaceFirstChar { it.uppercase() } else w }
    }

    override fun onPick(index: Int) {
        val ic = currentInputConnection ?: return
        val pick = shown.getOrNull(index) ?: return
        val word = currentWord()
        if (word.isEmpty()) { // a next-word suggestion: just add it
            ic.commitText("$pick ", 1)
            undo = null
            refreshAutoCaps(); refreshStrip()
            return
        }
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
        if (!select) dragAnchor = -1
        if (select && dx != 0 && dy == 0) {
            // Shift+arrow key events are ignored by many apps (browsers, custom editors), so select by setting the range directly.
            if (dragAnchor < 0) { dragAnchor = selStart; dragFocus = selEnd; if (selStart != selEnd && dx < 0) { dragAnchor = selEnd; dragFocus = selStart } }
            dragFocus = (dragFocus + dx).coerceAtLeast(0)
            if (currentInputConnection?.setSelection(minOf(dragAnchor, dragFocus), maxOf(dragAnchor, dragFocus)) == true) { undo = null; return }
            dragAnchor = -1
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
        val SECRET_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
        )
        val PRIVATE_VARIATIONS = setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_TEXT_VARIATION_FILTER,
        )
    }
}
