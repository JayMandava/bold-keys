package ai.boldkeys

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.view.inspector.WindowInspector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean

/** Real Android window/input-connection tests. No fake keyboard rendering or injected text commits. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TeaserSupplementCaptureTest {
    companion object {
        private val firstSettingsLaunch = AtomicBoolean(true)
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var imeObserver: CaptureImeWindowObserver
    private val output get() = File(context.getExternalFilesDir(null), "qa").apply { mkdirs() }
    private val palettes = listOf(KeyColour.RED, KeyColour.BLACK_GOLD, KeyColour.WHITE_GOLD, KeyColour.BLACK, KeyColour.WHITE)

    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            runCatching { capture("FAILED-${description.methodName}") }
            File(output, "FAILED-${description.methodName}.txt").writeText(error.stackTraceToString())
            result(description.methodName, "failed", error.toString())
            mirrorEvidence()
        }
        override fun succeeded(description: Description) {
            File(output, "PASS-${description.methodName}.txt").writeText("Passed on genuine Android: ${description.methodName}\n")
            result(description.methodName, "passed")
            mirrorEvidence()
        }
    }

    private fun result(test: String, status: String, error: String? = null) {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
        File(output, "result-$test.json").writeText(JSONObject().apply {
            put("test", test); put("status", status); put("package", context.packageName)
            put("version_name", info.versionName); put("version_code", info.longVersionCode)
            put("device_abis", android.os.Build.SUPPORTED_ABIS.joinToString(","))
            put("sdk", android.os.Build.VERSION.SDK_INT)
            put("ime_last_rejection",imeRejection)
            put("optional_context_model_enabled",Prefs.contextModel(context));put("optional_AI_runtime_demonstrated",false)
            put("evidence_kind", "real_android_windows_and_injected_touch")
            if (error != null) put("failure", error)
        }.toString(2) + "\n")
    }

    private fun <T> main(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val result = AtomicReference<T>()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try { result.set(block()) } catch (error: Throwable) { failure.set(error) }
        }
        failure.get()?.let { throw it }
        return result.get()
    }

    private fun waitFor(label: String, timeout: Long = 45_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        var last: Throwable? = null
        while (SystemClock.uptimeMillis() < deadline) {
            try { if (condition()) return } catch (error: Throwable) { last = error }
            SystemClock.sleep(100)
        }
        throw AssertionError("Timed out waiting for $label", last)
    }

    private var originalPrefs = emptyMap<String, Any?>()
    private var originalLearned = emptyMap<String, ByteArray?>()
    @Before fun setUp() {
        originalPrefs = context.getSharedPreferences("bk",0).all.toMap()
        originalLearned = listOf("en","fr","de").associate { lang ->
            val file=File(context.filesDir,Lang.learnedName(lang));file.name to file.takeIf { it.exists() }?.readBytes()
        }
        // All setting/shortcut/dictionary writes are confined to a disposable guest.
        Prefs.put(context,"contextModel",false)
        // Installed before Activity creation/show requests, including configuration recreations.
        main {
            imeObserver = CaptureImeWindowObserver(context.applicationContext as Application, ::imeFrame)
            imeObserver.register()
        }
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.setOrientationNatural()
        device.executeShellCommand("cmd uimode night yes")
        device.executeShellCommand("settings put system font_scale 1.0")
        device.executeShellCommand("ime enable ai.boldkeys/.BoldKeysService")
        device.executeShellCommand("ime set ai.boldkeys/.BoldKeysService")
        waitFor("Bold Keys selected") {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == "ai.boldkeys/.BoldKeysService"
        }
    }

    @After fun restoreDisplay() {
        runCatching { touch(MotionEvent.ACTION_CANCEL,listOf(PointF(0f,0f)),SystemClock.uptimeMillis()) }
        runCatching { device.setOrientationNatural() }
        runCatching { device.executeShellCommand("cmd uimode night no") }
        runCatching { device.executeShellCommand("settings put system font_scale 1.0") }
        if (::imeObserver.isInitialized) main { imeObserver.close() }
        val edit=context.getSharedPreferences("bk",0).edit().clear()
        for ((key,value) in originalPrefs) when(value) {
            is String -> edit.putString(key,value)
            is Boolean -> edit.putBoolean(key,value)
            is Int -> edit.putInt(key,value)
            is Long -> edit.putLong(key,value)
            is Float -> edit.putFloat(key,value)
        }
        edit.commit()
        for ((name,bytes) in originalLearned) {
            val file=File(context.filesDir,name)
            if(bytes==null) file.delete() else file.writeBytes(bytes)
        }
    }

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    /** TYPE_INPUT_METHOD excludes the Appearance preview, so only the actually connected IME counts. */
    private fun imeViews(): List<View> = main {
        WindowInspector.getGlobalWindowViews()
            .filter { (it.layoutParams as? WindowManager.LayoutParams)?.type == WindowManager.LayoutParams.TYPE_INPUT_METHOD }
            .flatMap { descendants(it).filter { view -> view.isShown && view.width > 0 && view.height > 0 }.toList() }
    }
    private fun keyboard() = imeViews().filterIsInstance<KeyboardView>().single()
    private fun bar() = imeViews().filterIsInstance<SuggestionBar>().single()
    private fun awaitIme() {
        main { imeObserver.beginAwait() }
        try {
            waitFor("bound, visible real IME after animation completion and stable frames") {
                main { imeObserver.ready() }
            }
            Log.i("ColourUiIme", "Input readiness verified: ${main { imeObserver.diagnostic() }}")
        } catch (error: AssertionError) {
            throw AssertionError("${error.message}; ${main { imeObserver.diagnostic() }}; lastRejection=$imeRejection", error)
        } finally {
            main { imeObserver.finishAwait() }
        }
    }

    /** Sampled only on distinct Choreographer frames, never by injecting a probe key. */
    @Volatile private var imeRejection="no frame sampled"
    private fun rejectIme(reason:String):ImeSettleState.Frame? { imeRejection=reason;return null }
    private fun imeFrame(decor: ViewGroup, observedImeBottom: Int): ImeSettleState.Frame? {
        val focused = decor.findFocus() ?: return rejectIme("focused client view missing")
        if (!decor.hasWindowFocus() || !focused.hasFocus()) return rejectIme("client window/view lacks focus")
        val imm = decor.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imm.isActive(focused)) return rejectIme("focused client is not active input editor")
        val views = imeViews()
        val kb = views.filterIsInstance<KeyboardView>().singleOrNull() ?: return rejectIme("missing or ambiguous visible IME keyboard")
        val strip = views.filterIsInstance<SuggestionBar>().singleOrNull() ?: return rejectIme("missing or ambiguous visible suggestion bar")
        val service = kb.context as? BoldKeysService ?: return rejectIme("keyboard service context missing")
        val editor = service.currentInputEditorInfo ?: return rejectIme("EditorInfo missing")
        if (!service.isInputViewShown || service.currentInputConnection == null ||
            editor.packageName != context.packageName || (focused is android.widget.EditText && editor.fieldId != focused.id)) return rejectIme("unbound or wrong-package/client input connection")
        val clientInsets = decor.rootWindowInsets ?: return rejectIme("focused client insets missing")
        val clientImeBottom = clientInsets.getInsets(WindowInsets.Type.ime()).bottom
        if (!CaptureImeGeometry.consistentInsets(clientInsets.isVisible(WindowInsets.Type.ime()),clientImeBottom,observedImeBottom))
            return rejectIme("focused client insets disagree: rawVisible=${clientInsets.isVisible(WindowInsets.Type.ime())} rawBottom=$clientImeBottom observedBottom=$observedImeBottom")
        // A Dialog above the keyboard legitimately has visible IME + zero local
        // overlap. Screen bounds must come from the actual input-method window,
        // never screenHeight minus another app window's bottom inset.
        val imeRoot=WindowInspector.getGlobalWindowViews().singleOrNull { root ->
            root.isShown && root.isAttachedToWindow && root.width>0 && root.height>0 &&
                (root.layoutParams as? WindowManager.LayoutParams)?.type==WindowManager.LayoutParams.TYPE_INPUT_METHOD &&
                kb.rootView===root && strip.rootView===root
        } ?: return rejectIme("no unique actual IME root containing keyboard and bar")
        val imeLocation=IntArray(2);imeRoot.getLocationOnScreen(imeLocation)
        val clientLocation=IntArray(2);decor.getLocationOnScreen(clientLocation)
        val keyLocation = IntArray(2); kb.getLocationOnScreen(keyLocation)
        val stripLocation = IntArray(2); strip.getLocationOnScreen(stripLocation)
        val width = device.displayWidth; val height = device.displayHeight
        val imeBounds=CaptureImeGeometry.Rect(imeLocation[0],imeLocation[1],imeLocation[0]+imeRoot.width,imeLocation[1]+imeRoot.height)
        val keyBounds=CaptureImeGeometry.Rect(keyLocation[0],keyLocation[1],keyLocation[0]+kb.width,keyLocation[1]+kb.height)
        val stripBounds=CaptureImeGeometry.Rect(stripLocation[0],stripLocation[1],stripLocation[0]+strip.width,stripLocation[1]+strip.height)
        if (!CaptureImeGeometry.stableLayoutCandidate(width,height,imeBounds,keyBounds,stripBounds))
            return rejectIme("real IME screen-space bounds invalid: ime=$imeBounds keyboard=$keyBounds bar=$stripBounds")
        val identity = "${System.identityHashCode(decor)}:${System.identityHashCode(focused)}:${editor.packageName}:${editor.fieldId}:${editor.inputType}"
        imeRejection="accepted focused bound window frame"
        return ImeSettleState.Frame(identity, listOf(width, height, decor.width, decor.height,
            clientLocation[0],clientLocation[1],clientImeBottom,observedImeBottom,
            imeLocation[0],imeLocation[1],imeRoot.width,imeRoot.height,stripLocation[0], stripLocation[1], strip.width, strip.height,
            keyLocation[0], keyLocation[1], kb.width, kb.height))
    }
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)

    private fun point(view: View, rect: RectF): PointF = main {
        val location = IntArray(2); view.getLocationOnScreen(location)
        PointF(location[0] + rect.centerX(), location[1] + rect.centerY())
    }
    private fun keyPoint(row: Int, column: Int): PointF = main {
        val kb = keyboard()
        val cells = field(kb, "cells") as List<*>
        val cell = (cells[row] as List<*>)[column]!!
        point(kb, field(cell, "rect") as RectF)
    }
    private fun tap(row: Int, column: Int) {
        awaitIme()
        val point = keyPoint(row, column)
        assertTrue("Key must be on screen: $row/$column ($point)", point.x in 0f..device.displayWidth.toFloat() && point.y in 0f..device.displayHeight.toFloat())
        assertTrue("Key tap injected", device.click(point.x.toInt(), point.y.toInt()))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        assertTrue("Screenshot $name", device.takeScreenshot(File(output, "$name.png")))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        val geometry = main {
            val views = imeViews()
            val kb = views.filterIsInstance<KeyboardView>().singleOrNull()
            val strip = views.filterIsInstance<SuggestionBar>().singleOrNull()
            val editor = KeyboardQaActivity.current.get()?.editor?.takeIf { it.isShown }
            val report = JSONObject().put("product_source_sha", "ce23cf74526b4864201d38503d2c9ad233d1787d")
                .put("density", context.resources.displayMetrics.density)
                .put("screen_width", device.displayWidth).put("screen_height",device.displayHeight)
                .put("editor_kind", "actual runtime Android windows; Notes heading for debug editor; genuine settings UI otherwise")
                .put("keyboard_mask", kb != null && strip != null)
            for ((label, view) in listOfNotNull(kb?.let { "keyboard" to it },strip?.let { "suggestion_bar" to it },editor?.let { "text_editor" to it })) {
                val xy=IntArray(2); view.getLocationOnScreen(xy)
                report.put(label,JSONObject().put("x",xy[0]).put("y",xy[1]).put("width",view.width).put("height",view.height))
            }
            if (kb != null && strip != null) {
                val mask=android.graphics.Bitmap.createBitmap(device.displayWidth,device.displayHeight,android.graphics.Bitmap.Config.ARGB_8888)
                val canvas=android.graphics.Canvas(mask)
                val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color=android.graphics.Color.WHITE }
                val xy=IntArray(2); strip.getLocationOnScreen(xy)
                canvas.drawRect(xy[0].toFloat(),xy[1].toFloat(),(xy[0]+strip.width).toFloat(),(xy[1]+strip.height).toFloat(),paint)
                kb.getLocationOnScreen(xy);canvas.save();canvas.translate(xy[0].toFloat(),xy[1].toFloat())
                canvas.drawPath(field(kb,"body") as android.graphics.Path,paint)
                if ((field(kb,"th") as KeyTheme).hasColouredFrame) {
                    paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=context.resources.displayMetrics.density*2.2f
                    paint.strokeCap=android.graphics.Paint.Cap.ROUND
                    canvas.drawPath(field(kb,"frame") as android.graphics.Path,paint)
                }
                canvas.restore()
                File(output,"$name-keyboard-mask.png").outputStream().use { mask.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                report.put("mask_kind","auxiliary coverage from actual runtime body/frame Paths; not screenshot or generated keyboard UI")
                    .put("edge_compositing","Screenshot is flattened over black. Use coverage mask with black-matte unpremultiplication at partial-alpha edges; never chroma-key all black pixels.")
            }
            report
        }
        File(output,"$name-geometry.json").writeText(geometry.toString(2)+"\n")
        mirrorEvidence()
    }
    private fun mirrorEvidence() {
        // Preserve evidence independently of UTP's optional test-app cleanup.
        // UiAutomation executes argv directly: &&, redirection and background operators are not shell syntax here.
        val destination = "/sdcard/Download/bold-keys-qa/evidence"
        val result = runCatching {
            device.executeShellCommand("mkdir -p $destination")
            device.executeShellCommand("cp -R ${output.absolutePath}/. $destination/")
            val listing = device.executeShellCommand("ls -1 $destination").lineSequence().toSet()
            val expected = output.listFiles()?.filter { it.isFile }?.map { it.name }.orEmpty()
            check(expected.isNotEmpty()) { "No local evidence files to mirror" }
            val missing = expected.filterNot { it in listing }
            check(missing.isEmpty()) { "Evidence mirror missing files: $missing; listing=$listing" }
            Log.i("ColourUiEvidence", "Verified ${expected.size} evidence filenames in $destination")
        }
        result.exceptionOrNull()?.let { error ->
            File(output, "evidence-copy-warning.txt").appendText(error.stackTraceToString() + "\n")
            Log.e("ColourUiEvidence", "Evidence mirror failed; do not assume captures survived cleanup", error)
        }
    }

    private fun launchSettings() {
        val firstLaunch = firstSettingsLaunch.getAndSet(false)
        val software = InstrumentationRegistry.getArguments().getString("softwareEmulator") == "true"
        val timeout = if (firstLaunch && software) 120_000L else 45_000L
        // Avoid MonitoringInstrumentation's fixed 45s cold-launch idle timeout. Still launch the real Activity,
        // and require its focused, accessible Home UI; this never dismisses an ANR or bypasses a UI assertion.
        context.startActivity(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("focused settings home", timeout) {
            setupWindowFocused() &&
                (device.findObject(By.pkg(context.packageName).text("Bold Keys")) != null ||
                    device.findObject(By.pkg(context.packageName).text("Try it")) != null)
        }
    }
    private fun setupWindowFocused(): Boolean {
        // UiDevice.currentPackageName is the last accessibility-event package, not window focus.
        val focus = device.executeShellCommand("dumpsys window").lineSequence()
            .map { it.trim() }.singleOrNull { it.startsWith("mCurrentFocus=") } ?: return false
        return Regex("""mCurrentFocus=Window\{[^}\s]+\s+u\d+\s+ai\.boldkeys/(?:\.SetupActivity|ai\.boldkeys\.SetupActivity)\}""")
            .matches(focus)
    }
    private fun textOnScreen(label: String): UiObject2 {
        repeat(10) {
            device.findObject(By.text(label))?.takeIf { it.visibleBounds.width() > 0 && it.visibleBounds.height() > 0 }?.let { return it }
            val scroll = device.findObject(By.scrollable(true))
            if (scroll != null) scroll.scroll(Direction.DOWN, 0.65f)
            else device.swipe(device.displayWidth * 3 / 4, device.displayHeight * 4 / 5, device.displayWidth * 3 / 4, device.displayHeight / 3, 30)
        }
        throw AssertionError("Could not find labelled control: $label")
    }
    private fun openAppearance() {
        launchSettings()
        textOnScreen("Appearance").click()
        waitFor("Appearance page") { device.findObject(By.text("Appearance")) != null }
        textOnScreen("Key colour")
    }
    private fun selected(node: UiObject2): Boolean {
        var current: UiObject2? = node
        repeat(5) {
            current?.let { if (it.isSelected || it.isChecked) return true; current = it.parent }
        }
        return false
    }
    private fun choose(id: String, label: String) {
        openAppearance()
        textOnScreen(label).click()
        waitFor("saved $label selection") { Prefs.theme(context) == id }
        waitFor("accessible selected state for $label") { device.findObject(By.text(label))?.let(::selected) == true }
    }
    private fun assertPalette(expected: KeyColour) {
        awaitIme()
        waitFor("$expected applied to both real IME surfaces") { main { (field(keyboard(), "th") as KeyTheme).colour == expected && (field(bar(), "th") as KeyTheme).colour == expected } }
        assertEquals(expected, main { (field(keyboard(), "th") as KeyTheme).colour })
        assertEquals(expected, main { (field(bar(), "th") as KeyTheme).colour })
    }
    private fun launchHost(mode: String = "text") {
        instrumentation.startActivitySync(Intent(context, KeyboardQaActivity::class.java).putExtra("mode", mode)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("synthetic $mode editor") { KeyboardQaActivity.current.get()?.let { main {
            it.editor.hasFocus() && (it.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).isActive(it.editor)
        } } == true }
        awaitIme()
        waitFor("IME bound to the new $mode editor") { main {
            val service = keyboard().context as BoldKeysService
            service.currentInputEditorInfo?.fieldId == editor().id && service.isInputViewShown
        } }
    }
    private fun editor() = KeyboardQaActivity.current.get()?.editor ?: error("No active synthetic editor")
    private fun text() = main { editor().text.toString() }
    private fun selection() = main { editor().selectionStart to editor().selectionEnd }
    private fun expectText(expected: String) = waitFor("editor text $expected") { text() == expected }

    private fun padPoint(): PointF = main {
        val view = bar()
        val rect = SuggestionBar::class.java.getDeclaredMethod("padRect").apply { isAccessible = true }.invoke(view) as RectF
        point(view, rect)
    }
    private fun injectTouch(action: Int, positions: List<PointF>, ids: List<Int>, downAt: Long, eventAt: Long):Boolean {
        require(positions.size == ids.size && ids.distinct().size == ids.size)
        val properties = ids.map { pointerId -> MotionEvent.PointerProperties().apply { id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = positions.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downAt, eventAt, action, positions.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return try { instrumentation.uiAutomation.injectInputEvent(event, true) } finally { event.recycle() }
    }
    private fun touch(action: Int, positions: List<PointF>, ids: List<Int>, downAt: Long, eventAt: Long) {
        assertTrue("Real touchscreen event injected: action=$action",injectTouch(action,positions,ids,downAt,eventAt))
    }
    private fun dragPadLeft(select: Boolean) {
        awaitIme()
        val pad = padPoint()
        val shift = if (select) keyPoint(3, 0) else null
        val density = main { bar().resources.displayMetrics.density }
        fun positions(sample: TrackpadGesturePlan.Sample) = sample.pointers.map { pointer ->
            if (pointer.role == TrackpadGesturePlan.Role.SHIFT) checkNotNull(shift)
            else PointF(pad.x + sample.padOffsetDp * density, pad.y)
        }
        val trace = main { TrackpadTouchTrace(bar(), keyboard(), editor(), Prefs.theme(context), select) }
        val traceFile = File(output, "trackpad-${Prefs.theme(context)}-${if (select) "select" else "move"}-${SystemClock.uptimeMillis()}.json")
        var last: TrackpadGesturePlan.TimedSample? = null
        var completed = false
        try {
            TrackpadGesturePlan.perform(select, SystemClock::uptimeMillis, SystemClock::sleep) { sample ->
                val action = when (sample.sample.action) {
                    TrackpadGesturePlan.Action.DOWN -> MotionEvent.ACTION_DOWN
                    TrackpadGesturePlan.Action.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (sample.sample.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    TrackpadGesturePlan.Action.MOVE -> MotionEvent.ACTION_MOVE
                    TrackpadGesturePlan.Action.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (sample.sample.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                    TrackpadGesturePlan.Action.UP -> MotionEvent.ACTION_UP
                }
                val points = positions(sample.sample)
                last = sample
                trace.injected(sample, action, points.map { it.x to it.y })
                touch(action, points, sample.sample.pointers.map { it.id }, sample.downTime, sample.eventTime)
            }
            completed = true
        } finally {
            // An interrupted gesture is cancelled, never retried or corrected with another drag.
            if (!completed) last?.let { sample ->
                runCatching { touch(MotionEvent.ACTION_CANCEL, positions(sample.sample), sample.sample.pointers.map { it.id }, sample.downTime, SystemClock.uptimeMillis()) }
                    .onFailure { Log.e("ColourUiTrackpad", "Could not cancel interrupted gesture", it) }
            }
            try { main { trace.close() } } finally { traceFile.writeText(trace.json()) }
        }
    }
    private fun touch(action: Int, positions: List<PointF>, downAt: Long) {
        touch(action, positions, positions.indices.toList(), downAt, SystemClock.uptimeMillis())
    }
    private fun pickerPoint(name: String): PointF = main {
        val kb = keyboard()
        val geometry = KeyboardView::class.java.getDeclaredMethod("pickGeo").apply { isAccessible = true }.invoke(kb)!!
        point(kb, field(geometry, name) as RectF)
    }
    private fun tapPicker(name: String) { awaitIme(); val point = pickerPoint(name); assertTrue(device.click(point.x.toInt(), point.y.toInt())) }
    private fun pickerOpen() = main { keyboard().pickerOpen() }

    private fun typeText(value: String, paced: Boolean = false) {
        for (character in value) {
            when (character) {
                ' ' -> tap(3, 2)
                '.' -> { tap(2, 0); tap(2, 7) }
                else -> {
                    if (!character.isLetter()) {
                        val cell = (0..2).flatMap { r -> (0..9).map { c -> r to c } }.first { (r,c) -> secondaryFor(r,c) == character.toString() }
                        tap(2,0); tap(cell.first,cell.second); continue
                    }
                    val row = Layouts.rows(Prefs.layout(context)).indexOfFirst { line -> line.any { it.label.equals(character.toString(), true) && it.kind == Kind.CHAR } }
                    require(row >= 0) { "No key for $character" }
                    val column = Layouts.rows(Prefs.layout(context))[row].indexOfFirst { it.label.equals(character.toString(), true) && it.kind == Kind.CHAR }
                    val upper = main { keyboard().shift != KeyboardView.Shift.OFF }
                    if (upper != character.isUpperCase()) tap(3, 0)
                    tap(row, column)
                }
            }
            if (paced) SystemClock.sleep(80) // a real recorded key sequence, not accelerated video
        }
    }
    private fun realTryField() {
        launchSettings()
        textOnScreen("Type here").click()
        awaitIme()
    }

    /** Required raw screenrecord. Startup/finalization failures fail the capture test. */
    private fun recording(name: String, action: () -> Unit) {
        require(name.matches(Regex("[a-z0-9-]+")))
        val dir = "/sdcard/Download/bold-keys-qa"
        val path = "$dir/$name.mp4"
        // Execute a script file, not a quoted `sh -c` string: Runtime.exec tokenizes whitespace itself.
        val startScript = File(output, "recording-$name-start.sh").apply {
            writeText("""
                #!/system/bin/sh
                mkdir -p "$dir"
                rm -f "$path" || exit 1
                screenrecord --verbose --bit-rate 6000000 --time-limit 60 "$path" >"$dir/$name.log" 2>&1 </dev/null &
                echo ${'$'}!
            """.trimIndent() + "\n")
        }
        val notes = StringBuilder("Raw recording path: $path\n")
        val pid = runCatching { device.executeShellCommand("sh ${startScript.absolutePath}").trim() }.getOrDefault("")
        val started = pid.matches(Regex("[0-9]+"))
        val observations = org.json.JSONArray()
        var ownedStartTicks: Long? = null
        fun sample(): Pair<RecorderStartupGate.Process?, Long?> {
            val process = if (started) RecorderStartupGate.parseStat(runCatching {
                device.executeShellCommand("cat /proc/$pid/stat").trim()
            }.getOrDefault("")) else null
            val bytes = runCatching {
                device.executeShellCommand("stat -c %s $path").trim().toLongOrNull()
            }.getOrNull()
            if (ownedStartTicks == null && process?.live == true) ownedStartTicks = process.startTicks
            if (observations.length() < 128) observations.put(JSONObject()
                .put("uptime_ms", SystemClock.uptimeMillis())
                .put("process_state", process?.state ?: "missing-or-unreadable")
                .put("process_name_matches", process?.name == "screenrecord")
                .put("start_ticks", process?.startTicks ?: JSONObject.NULL)
                .put("output_bytes", bytes ?: JSONObject.NULL))
            return process to bytes
        }
        fun alive(): Boolean {
            val process = sample().first
            return process?.live == true && process.startTicks == ownedStartTicks
        }
        val ready = runCatching {
            check(started) { "Recorder did not return a PID" }
            val deadline = SystemClock.uptimeMillis() + 10_000
            val gate = RecorderStartupGate()
            var stable = false
            while (SystemClock.uptimeMillis() < deadline) {
                val (process, bytes) = sample()
                // A static settings screen may buffer encoded samples until
                // SIGINT. A fresh opened file and stable process identify a
                // started recorder; bytes do not certify encoded frames.
                if (gate.observe(SystemClock.uptimeMillis(), process, bytes != null)) {
                    stable = true; break
                }
                SystemClock.sleep(250)
            }
            check(stable && alive()) { "Recorder did not survive live preroll with an opened output file within ten seconds; inspect process observations" }
        }
        notes.append("Recorder preroll: ").append(ready.exceptionOrNull()?.toString() ?: "same live process and fresh output file observed through preroll; frames unverified").append('\n')
        var actionPassed = false
        try {
            ready.getOrThrow()
            action() // Real touch actions only, after a verified live recorder/preroll.
            check(alive()) { "Recorder exited during recorded actions" }
            SystemClock.sleep(1200) // hold the genuine completed UI in the recording
            check(alive()) { "Recorder exited during completed UI hold" }
            actionPassed = true
        } finally {
            val stopped = runCatching {
                if (alive()) device.executeShellCommand("kill -INT $pid")
                val deadline = SystemClock.uptimeMillis() + 10_000
                while (alive() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(250)
                check(!alive()) { "Recorder did not finalize within ten seconds; do not use this clip" }
                device.executeShellCommand("ls -l $path")
            }
            notes.append("Recorder finalization: ").append(stopped.fold({ it }, { it.toString() })).append('\n')
            notes.append("Verify decoding and actual action coverage before using; UI tests do not certify video encoding.\n")
            File(output, "recording-$name.txt").writeText(notes.toString())
            File(output,"recording-$name.json").writeText(JSONObject()
                .put("clip", "$name.mp4").put("action_assertions_passed",actionPassed)
                .put("recorder_startup_verified",ready.isSuccess).put("recorder_finalized",stopped.isSuccess)
                .put("startup_failure",ready.exceptionOrNull()?.message ?: JSONObject.NULL)
                .put("process_observations",observations)
                .put("startup_proves","same live recorder, fresh opened output, stable preroll; decoded frames checked after finalization")
                .put("classification",if(name.startsWith("dev-")) "development-preview" else "public-feature")
                .put("visual_review_required",true).put("producer","Android screenrecord; actual injected touchscreen events")
                .toString(2)+"\n")
            mirrorEvidence()
            stopped.getOrThrow()
        }
    }


    private fun prepareNotes(colour: KeyColour) {
        Prefs.put(context, "theme", colour.id)
        Prefs.put(context, "layout", "qwerty")
        Prefs.put(context, "language", "en")
        Prefs.put(context, "keySize", "normal")
        Prefs.put(context, "contextModel", false)
        Prefs.put(context, "emojiPages", true)
        Prefs.put(context, "autocorrect", true)
        Prefs.put(context, "suggestions", true)
        Prefs.put(context, "backlight", "off")
        Prefs.put(context, "pattern", "off")
        launchHost()
        main {
            val parent = editor().parent as android.view.ViewGroup
            (parent.getChildAt(0) as android.widget.TextView).text = "Notes"
            editor().hint = "A little inspiration"
        }
        assertPalette(colour)
    }

    private fun notesWithCurrentSettings() {
        launchHost()
        main { ((editor().parent as ViewGroup).getChildAt(0) as android.widget.TextView).text="Notes" }
    }
    private fun baseSettings(page: String) {
        prepareNotes(KeyColour.BLACK)
        engineReady("en")
        launchSettings(); textOnScreen(page).click()
    }
    private fun choice(label: String, saved: () -> Boolean) {
        textOnScreen(label).click()
        waitFor("saved and selected $label") { saved() && device.findObject(By.text(label))?.let(::selected)==true }
        SystemClock.sleep(350)
    }
    private fun engineReady(lang: String) {
        waitFor("loaded $lang dictionary") { main {
            val service=keyboard().context as BoldKeysService
            field(service,"engineLang")==lang && field(service,"engine")!=null
        } }
    }
    private fun languageShot(name:String, layout:String, label:String, lang:String, typo:String, corrected:String) {
        baseSettings("Typing")
        recording(name) {
            choice(label) { Prefs.layout(context)==layout }
            choice("Auto") { Prefs.languageChoice(context)=="auto" }
            assertEquals(lang,Prefs.language(context))
            capture("$name-settings")
            notesWithCurrentSettings();engineReady(lang)
            assertEquals(layout,main { field(keyboard(),"layoutId") })
            typeText(typo,paced=true);expectText(typo)
            tap(3,2);expectText("$corrected ")
            capture("$name-correction")
        }
    }
    @Test fun a_germanLayoutAndCorrection() = languageShot("supp-german","qwertz","QWERTZ","de","fur","für")
    @Test fun b_frenchLayoutAndCorrection() = languageShot("supp-french","azerty","AZERTY","fr","ete","été")

    @Test fun c_explicitLanguageAndQwerty() {
        baseSettings("Typing")
        recording("supp-language-override") {
            choice("QWERTY") { Prefs.layout(context)=="qwerty" }
            for ((id,label) in listOf("fr" to "Français","de" to "Deutsch","en" to "English")) {
                choice(label) { Prefs.languageChoice(context)==id }
                assertEquals(id,Prefs.language(context));capture("supp-language-$id")
            }
            notesWithCurrentSettings();engineReady("en")
            assertEquals("qwerty",main { field(keyboard(),"layoutId") })
            typeText("hello",paced=true);expectText("hello");capture("supp-qwerty-result")
        }
    }
    @Test fun d_keySizes() {
        baseSettings("Appearance")
        recording("supp-key-size") {
            choice("Small") { Prefs.keySize(context)=="small" }
            notesWithCurrentSettings()
            val small=main { keyboard().width to keyboard().height }
            typeText("hello");expectText("hello");capture("supp-key-size-small")
            openAppearance()
            for ((id,label) in listOf("normal" to "Normal","large" to "Large","xlarge" to "XL"))
                choice(label) { Prefs.keySize(context)==id }
            notesWithCurrentSettings()
            val large=main { keyboard().width to keyboard().height }
            assertEquals(small.first,large.first);assertTrue("XL is taller",large.second>small.second)
            assertEquals(1.25f,main { field(keyboard(),"sizeScale") as Float },0.001f)
            typeText("hello");expectText("hello");capture("supp-key-size-xl")
            File(output,"result-size-measurements.json").writeText(JSONObject()
                .put("small_width",small.first).put("small_height",small.second)
                .put("xl_width",large.first).put("xl_height",large.second).toString(2))
        }
    }
    private fun brightnessSlider(): UiObject2 {
        textOnScreen("Brightness")
        repeat(4) {
            device.findObject(By.clazz("android.widget.SeekBar"))?.takeIf {
                it.visibleBounds.height()>10 && it.visibleBounds.width()>100
            }?.let { return it }
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,0.25f)
        }
        error("No visible accessible brightness slider")
    }
    @Test fun e_backlight() {
        baseSettings("Appearance")
        recording("supp-backlight") {
            choice("Off") { Prefs.backlightMode(context)=="off" }
            notesWithCurrentSettings();assertEquals(0f,main { field(keyboard(),"glow") as Float },0.001f)
            capture("supp-backlight-off")
            openAppearance();choice("On") { Prefs.backlightMode(context)=="on" }
            val slider=brightnessSlider()
            val old=Prefs.backlightLevel(context);val bounds=slider.visibleBounds
            val fraction=if(old>0.5f) 0.25f else 0.8f
            assertTrue(device.click((bounds.left+bounds.width()*fraction).toInt(),bounds.centerY()))
            waitFor("brightness saved") { kotlin.math.abs(Prefs.backlightLevel(context)-old)>0.05f }
            notesWithCurrentSettings()
            assertEquals("on",main { field(keyboard(),"glowMode") })
            assertTrue(main { field(keyboard(),"glow") as Float }>0f)
            assertEquals(Prefs.backlightLevel(context),main { field(keyboard(),"glow") as Float },0.02f)
            capture("supp-backlight-on")
            openAppearance();choice("Auto") { Prefs.backlightMode(context)=="auto" }
            capture("supp-backlight-auto-settings") // settings only; no ambient-sensor response claim
        }
    }
    @Test fun f_hapticSettings() {
        baseSettings("Key feel")
        recording("supp-haptic-settings") {
            for ((id,label) in listOf("off" to "Off","ms3" to "3 ms","double" to "Double tap")) {
                choice(label) { Prefs.string(context,"pattern","")==id }
                capture("supp-haptic-$id")
            }
            textOnScreen("Try the click").click()
            assertEquals("double",Prefs.string(context,"pattern",""))
            capture("supp-haptic-try-control") // visible settings, not physical sensation
        }
    }
    private fun shortcutPage(letter:String) {
        launchSettings();textOnScreen("Speed key").click();textOnScreen(letter).click()
        waitFor("shortcut dialog") { device.findObject(By.text("Speed key + $letter"))!=null }
        main { imeObserver.observeWindows() } // install Dialog sibling probe before focusing its field
    }
    private fun focusedPackage():String? = device.executeShellCommand("dumpsys window").lineSequence()
        .firstOrNull { it.trim().startsWith("mCurrentFocus=") }
        ?.let { Regex("u[0-9]+ ([A-Za-z0-9_.]+)/").find(it)?.groupValues?.get(1) }
    private fun speedLetter(letter:Char,expectedExternal:String?=null) {
        awaitIme()
        val rows=Layouts.rows(Prefs.layout(context))
        val row=rows.indexOfFirst { line -> line.any { it.label==letter.toString() } }
        val column=rows[row].indexOfFirst { it.label==letter.toString() }
        val speed=keyPoint(3,4);val target=keyPoint(row,column);val down=SystemClock.uptimeMillis()
        val before=focusedPackage();val events=org.json.JSONArray()
        check(before==context.packageName) { "Speed gesture must start in the verified own editor window" }
        fun deliver(action:Int,points:List<PointF>) {
            // Security/permission exceptions propagate; only a false return caused
            // by a separately verified expected window handoff is permissible.
            val attempt=runCatching { injectTouch(action,points,points.indices.toList(),down,SystemClock.uptimeMillis()) }
            val observation=JSONObject().put("action",action).put("delivered",attempt.getOrDefault(false))
                .put("injection_exception",attempt.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("foreground_before",before ?: JSONObject.NULL).put("foreground_after",focusedPackage() ?: JSONObject.NULL)
            events.put(observation)
            val delivered=attempt.getOrThrow()
            if(!delivered && expectedExternal!=null && action!=MotionEvent.ACTION_DOWN)
                waitFor("expected external window handoff",5000) { focusedPackage()==expectedExternal }
            val after=focusedPackage();observation.put("foreground_after_wait",after ?: JSONObject.NULL)
            CaptureHarnessRules.requireDelivery(delivered,if(action==MotionEvent.ACTION_DOWN) null else expectedExternal,after,before)
        }
        try {
            deliver(MotionEvent.ACTION_DOWN,listOf(speed));SystemClock.sleep(120)
            deliver(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(speed,target))
            SystemClock.sleep(100)
            deliver(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),listOf(speed,target))
            deliver(MotionEvent.ACTION_UP,listOf(speed))
        } finally {
            // Best-effort release cannot replace the original failure. A lost
            // IME window after a successful App/Link launch has no touch target.
            val cleanup=runCatching { injectTouch(MotionEvent.ACTION_CANCEL,listOf(speed),listOf(0),down,SystemClock.uptimeMillis()) }
            events.put(JSONObject().put("action",MotionEvent.ACTION_CANCEL)
                .put("delivered",cleanup.getOrDefault(false)).put("cleanup_error",cleanup.exceptionOrNull()?.javaClass?.simpleName ?: JSONObject.NULL))
            File(output,"result-speed-$letter-events.json").writeText(events.toString(2)+"\n")
            mirrorEvidence()
        }
    }
    @Test fun g_configureAndRunTextShortcut() {
        prepareNotes(KeyColour.BLACK);shortcutPage("B")
        recording("supp-shortcut-text") {
            textOnScreen("Text").click();textOnScreen("Text to type").click();awaitIme()
            typeText("See you soon.",paced=true)
            device.pressBack();textOnScreen("Save").click()
            waitFor("text assignment saved") { Shortcuts.get(context,'B')?.let { it.type==Shortcut.TEXT && it.value=="See you soon." }==true }
            capture("supp-shortcut-text-configured")
            notesWithCurrentSettings();speedLetter('B');expectText("See you soon.")
            capture("supp-shortcut-text-result")
        }
    }
    @Test fun h_configureAndRunAppShortcut() {
        prepareNotes(KeyColour.BLACK)
        val target=context.packageManager.getLaunchIntentForPackage("com.android.settings")
        assertNotNull("Guest must expose a stock Settings launcher",target)
        val label=context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo("com.android.settings",0)).toString()
        assertTrue(Shortcuts.launchableApps(context).any { it.second=="com.android.settings" })
        shortcutPage("A")
        recording("supp-shortcut-app") {
            textOnScreen("App").click();textOnScreen(label).click()
            waitFor("App assignment saved") { Shortcuts.get(context,'A')?.let { it.type==Shortcut.APP && it.value=="com.android.settings" }==true }
            capture("supp-shortcut-app-configured")
            notesWithCurrentSettings();speedLetter('A',"com.android.settings")
            waitFor("stock Settings launched by speed gesture") { focusedPackage()=="com.android.settings" }
            capture("supp-shortcut-app-result")
        }
    }
    @Test fun i_configureAndRunLinkShortcut() {
        prepareNotes(KeyColour.BLACK);shortcutPage("C")
        val uri="https://www.android.com"
        val resolution=device.executeShellCommand("cmd package resolve-activity --brief -a android.intent.action.VIEW -c android.intent.category.BROWSABLE -d $uri").trim().lineSequence().last()
        check(Regex("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+").matches(resolution)) { "No unambiguous safe HTTPS handler: link capture unavailable" }
        val browser=resolution.substringBefore('/')
        check(browser!="android") { "HTTPS resolver/chooser is not a selected browser; Link shot unavailable" }
        recording("supp-shortcut-link") {
            textOnScreen("Link").click();textOnScreen("Link or action").click();awaitIme()
            typeText("www.android.com",paced=true);device.pressBack();textOnScreen("Save").click()
            waitFor("Link assignment saved") { Shortcuts.get(context,'C')?.let { it.type==Shortcut.LINK && it.value=="www.android.com" }==true }
            capture("supp-shortcut-link-configured")
            notesWithCurrentSettings();speedLetter('C',browser)
            waitFor("HTTPS handler launched") { focusedPackage()==browser }
            waitFor("official URL intent delivered") { device.executeShellCommand("dumpsys activity activities").contains("dat=$uri") }
            check(device.findObject(By.textContains("Welcome to Chrome"))==null) { "Browser onboarding obscures link result; no page-load claim" }
            capture("supp-shortcut-link-result") // handler/intent only; no page/network performance assertion
        }
    }
    @Test fun j_personalDictionaryAddRemoveClear() {
        baseSettings("Personal dictionary")
        // Only disposable guest words. Baseline files are restored in @After.
        recording("supp-personal-dictionary") {
            textOnScreen("A name, a place, slang…").click();awaitIme();typeText("zorbital",paced=true)
            device.pressBack();textOnScreen("Add").click()
            val learned=File(context.filesDir,Lang.learnedName("en"))
            waitFor("word saved") { learned.exists() && learned.readText().contains("zorbital\t5") }
            capture("supp-dictionary-added")
            notesWithCurrentSettings();engineReady("en");typeText("zor",paced=true)
            waitFor("added word suggested") { main { (field(bar(),"words") as List<*>).contains("zorbital") } }
            capture("supp-dictionary-suggestion")
            launchSettings();textOnScreen("Personal dictionary").click()
            val remove=device.findObject(By.desc("Remove zorbital")) ?: error("No Remove zorbital control")
            remove.click();waitFor("word removed") { !learned.readText().contains("zorbital\t") }
            textOnScreen("A name, a place, slang…").click();awaitIme();typeText("zorbital")
            device.pressBack();textOnScreen("Add").click()
            textOnScreen("Clear all words").click();textOnScreen("Remove all").click()
            waitFor("dictionary cleared") { learned.readText().isBlank() };capture("supp-dictionary-cleared")
        }
    }
    @Test fun k_nextWordTrackpadAcceptance() {
        prepareNotes(KeyColour.BLACK);engineReady("en")
        recording("supp-next-word") {
            typeText("thank ",paced=true)
            waitFor("next-word suggestions") { main { (field(bar(),"words") as List<*>).firstOrNull()=="you" } }
            capture("supp-next-word-before")
            val p=padPoint();assertTrue(device.click(p.x.toInt(),p.y.toInt()))
            expectText("thank you ");capture("supp-next-word-accepted")
        }
    }
    @Test fun l_doubleSpaceAndSentenceCapital() {
        prepareNotes(KeyColour.BLACK);engineReady("en")
        // The product follows the editor's cursor-caps request. The default QA
        // field omitted CAP_SENTENCES, so expecting automatic Shift was invalid.
        main {
            editor().inputType=editor().inputType or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            (context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager).restartInput(editor())
        }
        awaitIme()
        assertTrue(main { (keyboard().context as BoldKeysService).currentInputEditorInfo!!.inputType and android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0 })
        recording("supp-double-space") {
            typeText("hello",paced=true);tap(3,2);SystemClock.sleep(100);tap(3,2)
            expectText("hello. ")
            waitFor("auto capital for next sentence") { main { keyboard().shift==KeyboardView.Shift.ONCE } }
            tap(1,5);typeText("ello");expectText("hello. Hello");capture("supp-double-space-capital")
        }
    }
    @Test fun m_britishSpelling() {
        baseSettings("Typing")
        recording("supp-british-spelling") {
            choice("British") { Prefs.spelling(context)=="uk" }
            notesWithCurrentSettings();engineReady("en");typeText("color ",paced=true);expectText("colour ")
            capture("supp-british-result")
            launchSettings();textOnScreen("Typing").click();choice("American") { Prefs.spelling(context)=="us" }
            notesWithCurrentSettings();engineReady("en");typeText("colour ",paced=true);expectText("color ")
            capture("supp-american-result")
        }
    }
    @Test fun n_altLockAndSingleShift() {
        prepareNotes(KeyColour.BLACK)
        recording("supp-alt-lock") {
            val alt=keyPoint(2,0);val down=SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN,listOf(alt),down);SystemClock.sleep(650);touch(MotionEvent.ACTION_UP,listOf(alt),down)
            waitFor("Alt locked") { main { field(keyboard(),"altMode")==KeyboardView.Shift.CAPS } }
            tap(0,1);tap(0,2);tap(0,3);expectText("123")
            tap(2,0);waitFor("Alt unlocked") { main { field(keyboard(),"altMode")==KeyboardView.Shift.OFF } }
            tap(3,0);tap(0,0);tap(0,1);expectText("123Qw");capture("supp-alt-lock-result")
        }
    }
    @Test fun o_emoticonAndUndo() {
        prepareNotes(KeyColour.BLACK)
        recording("supp-emoticon") {
            typeText(":)",paced=true);expectText("🙂");capture("supp-emoticon-converted")
            tap(1,9);expectText(":)");capture("supp-emoticon-undo")
        }
    }
    @Test fun p_currencySettingAndTap() {
        baseSettings("Symbols & emoji")
        recording("supp-currency") {
            choice("₹") { Prefs.currencyChoice(context)=="₹" }
            notesWithCurrentSettings()
            assertEquals("₹",main { field(keyboard(),"currency") })
            val audio=context.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
            val before=audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
            tap(2,8);expectText("₹");assertEquals(before,audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC))
            capture("supp-currency-result")
        }
    }
    @Test fun q_pickerFingerAndTrackpadScroll() {
        prepareNotes(KeyColour.BLACK)
        recording("supp-picker-scroll") {
            tap(3,3);waitFor("symbol picker") { pickerOpen() }
            val initial=main { field(keyboard(),"pickScroll") as Float }
            val grid=main {
                val g=KeyboardView::class.java.getDeclaredMethod("pickGeo").apply { isAccessible=true }.invoke(keyboard())!!
                val rect=field(g,"grid") as RectF
                val origin=IntArray(2);keyboard().getLocationOnScreen(origin)
                android.graphics.Rect((origin[0]+rect.left).toInt(),(origin[1]+rect.top).toInt(),(origin[0]+rect.right).toInt(),(origin[1]+rect.bottom).toInt())
            }
            device.swipe(grid.centerX(),grid.bottom-20,grid.centerX(),grid.top+20,25)
            waitFor("actual picker finger scroll") { main { (field(keyboard(),"pickScroll") as Float)>initial+1 } }
            waitFor("finger fling and glide settled") { main { field(keyboard(),"gliding")==false && field(keyboard(),"flinging")==false } }
            capture("supp-picker-finger")
            val before=main { field(keyboard(),"pickScroll") as Float }
            val pad=padPoint();val density=context.resources.displayMetrics.density
            val direction=if(before>0f) -1 else 1
            val down=SystemClock.uptimeMillis()
            touch(MotionEvent.ACTION_DOWN,listOf(pad),down)
            try {
                for(offset in CaptureHarnessRules.verticalOffsets(direction).drop(1)) {
                    SystemClock.sleep(35)
                    touch(MotionEvent.ACTION_MOVE,listOf(PointF(pad.x,pad.y+offset*density)),down)
                }
                touch(MotionEvent.ACTION_UP,listOf(PointF(pad.x,pad.y+direction*32*density)),down)
            } finally { runCatching { touch(MotionEvent.ACTION_CANCEL,listOf(pad),down) } }
            waitFor("actual picker trackpad scroll") { main { kotlin.math.abs((field(keyboard(),"pickScroll") as Float)-before)>1 } }
            capture("supp-picker-trackpad");tapPicker("close");assertFalse(pickerOpen());expectText("")
        }
    }

    private fun liveColourControls(count:Int):List<UiObject2> {
        textOnScreen("Brightness")
        val candidates=device.findObjects(By.clickable(true))
        val bounds=candidates.map { it.visibleBounds.let { b -> CaptureHarnessRules.Bounds(b.left,b.top,b.right,b.bottom) } }
        val brightness=checkNotNull(device.findObject(By.text("Brightness"))).visibleBounds
        // Re-query section after scrolling: all coordinates come from live UI.
        val top=device.findObject(By.text("Backlight"))?.visibleBounds?.bottom ?: 0
        val indices=CaptureHarnessRules.swatchIndices(bounds,top,brightness.top,count)
        File(output,"result-backlight-control-bounds.json").writeText(JSONObject()
            .put("section_top",top).put("brightness_top",brightness.top)
            .put("candidates",org.json.JSONArray(bounds.map { listOf(it.left,it.top,it.right,it.bottom) }))
            .put("matched_indices",org.json.JSONArray(indices)).toString(2))
        check(indices.size==count) { "No unique complete live colour row; inspect bounds evidence (nominal layout size is not accessibility touch bounds)" }
        return indices.map { candidates[it] }
    }
    @Test fun r_backlightColours() {
        baseSettings("Appearance")
        recording("supp-backlight-colours") {
            choice("On") { Prefs.backlightMode(context)=="on" }
            textOnScreen("Brightness") // scroll swatches and slider into view
            val colours=Prefs.BACKLIGHT_COLORS.keys.toList()
            for ((index,id) in colours.withIndex()) {
                val swatches=liveColourControls(colours.size)
                swatches[index].click();waitFor("saved $id backlight") { Prefs.backlightColor(context)==id }
                capture("supp-backlight-colour-$id")
            }
            notesWithCurrentSettings()
            assertEquals(Prefs.BACKLIGHT_COLORS.getValue(colours.last()),main { field(keyboard(),"glowRgb") })
            assertTrue(main { field(keyboard(),"glow") as Float }>0f)
            capture("supp-backlight-colour-result")
        }
    }
    @Test fun s_matchPhoneTheme() {
        baseSettings("Appearance")
        recording("supp-match-phone") {
            choice("Match phone") { Prefs.theme(context)=="auto" }
            notesWithCurrentSettings();assertPalette(KeyColour.BLACK);capture("supp-match-phone-dark")
            device.executeShellCommand("cmd uimode night no")
            awaitIme();assertPalette(KeyColour.WHITE);capture("supp-match-phone-light")
        }
    }
    @Test fun t_defaultToneAndRightShiftFallback() {
        baseSettings("Symbols & emoji")
        recording("supp-tone-key-settings") {
            choice("👍🏽") { Emoji.defaultTone(context)==3 }
            capture("supp-default-tone-setting")
            launchSettings();textOnScreen("Symbols & emoji").click()
            textOnScreen("Emoji key").click();waitFor("emoji key off") { !Prefs.emojiPages(context) }
            notesWithCurrentSettings()
            // Default auto-capitals may already be ONCE: establish OFF by a real tap.
            if(main { keyboard().shift!=KeyboardView.Shift.OFF }) tap(3,0)
            tap(3,4);assertFalse(pickerOpen())
            assertEquals(KeyboardView.Shift.ONCE,main { keyboard().shift })
            tap(0,0);tap(0,1);expectText("Qw");capture("supp-right-shift-fallback")
        }
    }
    @Test fun u_sensitiveFields() {
        prepareNotes(KeyColour.BLACK)
        recording("supp-sensitive-fields") {
            for(mode in listOf("email","password","url")) {
                notesHostMode(mode)
                typeText("teh",paced=true);tap(3,2)
                expectText("teh ")
                if(mode!="url") assertTrue(main { (field(bar(),"words") as List<*>).isEmpty() })
                // URI fields intentionally allow suggestions while disabling rewriting.
                capture("supp-sensitive-$mode")
            }
        }
    }
    private fun notesHostMode(mode:String) {
        launchHost(mode)
        main { ((editor().parent as ViewGroup).getChildAt(0) as android.widget.TextView).text="Notes" }
    }

    @Test fun v_automaticLearning() {
        baseSettings("Typing")
        recording("supp-automatic-learning") {
            textOnScreen("Autocorrect").click();waitFor("autocorrect off") { !Prefs.autocorrect(context) }
            notesWithCurrentSettings();engineReady("en")
            typeText("zorbital zorbital ",paced=true);expectText("zorbital zorbital ")
            val learned=File(context.filesDir,Lang.learnedName("en"))
            waitFor("unknown word learned from actual typing twice") { learned.exists() && learned.readText().lineSequence().any {
                val pair=it.split('\t');pair.size==2 && pair[0]=="zorbital" && (pair[1].toIntOrNull()?:0)>=2
            } }
            capture("supp-automatic-learning-result")
        }
    }
    @Test fun w_directSuggestionAcceptance() {
        prepareNotes(KeyColour.BLACK);engineReady("en")
        recording("supp-suggestion-tap") {
            typeText("hel",paced=true)
            waitFor("completion available") { main { (field(bar(),"words") as List<*>).isNotEmpty() } }
            val candidate=main { (field(bar(),"words") as List<*>).first() as String }
            val target=main {
                val view=bar();val pad=SuggestionBar::class.java.getDeclaredMethod("padRect").apply { isAccessible=true }.invoke(view) as RectF
                val x=(pad.left-context.resources.displayMetrics.density*8)/2
                val xy=IntArray(2);view.getLocationOnScreen(xy)
                PointF(xy[0]+x,xy[1]+view.height/2f)
            }
            capture("supp-suggestion-before")
            assertTrue(device.click(target.x.toInt(),target.y.toInt()));expectText("$candidate ")
            capture("supp-suggestion-accepted")
        }
    }
}
