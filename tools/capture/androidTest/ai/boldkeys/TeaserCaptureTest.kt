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
class TeaserCaptureTest {
    companion object {
        private val firstSettingsLaunch = AtomicBoolean(true)
    }
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private lateinit var imeObserver: ImeWindowObserver
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

    @Before fun setUp() {
        // Installed before Activity creation/show requests, including configuration recreations.
        main {
            imeObserver = ImeWindowObserver(context.applicationContext as Application, ::imeFrame)
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
            throw AssertionError("${error.message}; ${main { imeObserver.diagnostic() }}", error)
        } finally {
            main { imeObserver.finishAwait() }
        }
    }

    /** Sampled only on distinct Choreographer frames, never by injecting a probe key. */
    private fun imeFrame(activity: Activity, observedImeBottom: Int): ImeSettleState.Frame? {
        val decor = activity.window.decorView
        val focused = decor.findFocus() ?: return null
        if (!decor.hasWindowFocus() || !focused.hasFocus()) return null
        val clientInsets = decor.rootWindowInsets ?: return null
        val clientImeBottom = clientInsets.getInsets(WindowInsets.Type.ime()).bottom
        if (!clientInsets.isVisible(WindowInsets.Type.ime()) || observedImeBottom <= 0 ||
            clientImeBottom != observedImeBottom) return null
        val imm = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (!imm.isActive(focused)) return null
        val views = imeViews()
        val kb = views.filterIsInstance<KeyboardView>().singleOrNull() ?: return null
        val strip = views.filterIsInstance<SuggestionBar>().singleOrNull() ?: return null
        val service = kb.context as? BoldKeysService ?: return null
        val editor = service.currentInputEditorInfo ?: return null
        if (!service.isInputViewShown || service.currentInputConnection == null ||
            editor.packageName != context.packageName || editor.fieldId != focused.id) return null
        val keyLocation = IntArray(2); kb.getLocationOnScreen(keyLocation)
        val stripLocation = IntArray(2); strip.getLocationOnScreen(stripLocation)
        val width = device.displayWidth; val height = device.displayHeight
        if (kb.width < width * 0.8f || keyLocation[0] < 0 || keyLocation[1] < 0 ||
            keyLocation[0] + kb.width > width || keyLocation[1] + kb.height > height ||
            stripLocation[0] < 0 || stripLocation[1] < height - observedImeBottom ||
            stripLocation[0] + strip.width > width || stripLocation[1] + strip.height > keyLocation[1]) return null
        val identity = "${System.identityHashCode(activity)}:${System.identityHashCode(focused)}:${editor.packageName}:${editor.fieldId}:${editor.inputType}"
        return ImeSettleState.Frame(identity, listOf(width, height, decor.width, decor.height,
            observedImeBottom, stripLocation[0], stripLocation[1], strip.width, strip.height,
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
                .put("editor_kind", "actual dark debug EditText; neutral Notes heading; no injected text during recordings")
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
    private fun touch(action: Int, positions: List<PointF>, ids: List<Int>, downAt: Long, eventAt: Long) {
        require(positions.size == ids.size && ids.distinct().size == ids.size)
        val properties = ids.map { pointerId -> MotionEvent.PointerProperties().apply { id = pointerId; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = positions.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downAt, eventAt, action, positions.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Real touchscreen event injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
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
                    val row = Layouts.letterRows.indexOfFirst { line -> line.any { it.label.equals(character.toString(), true) && it.kind == Kind.CHAR } }
                    require(row >= 0) { "No key for $character" }
                    val column = Layouts.letterRows[row].indexOfFirst { it.label.equals(character.toString(), true) && it.kind == Kind.CHAR }
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
                screenrecord --verbose --bit-rate 6000000 --time-limit 60 "$path" >"$dir/$name.log" 2>&1 </dev/null &
                echo ${'$'}!
            """.trimIndent() + "\n")
        }
        val notes = StringBuilder("Raw recording path: $path\n")
        val pid = runCatching { device.executeShellCommand("sh ${startScript.absolutePath}").trim() }.getOrDefault("")
        val started = pid.matches(Regex("[0-9]+"))
        fun alive() = started && runCatching {
            device.executeShellCommand("cat /proc/$pid/comm").trim() == "screenrecord"
        }.getOrDefault(false)
        val ready = runCatching {
            check(started) { "Recorder did not return a PID" }
            val deadline = SystemClock.uptimeMillis() + 10_000
            var outputBytes = false
            while (SystemClock.uptimeMillis() < deadline) {
                val size = device.executeShellCommand("stat -c %s $path").trim().toLongOrNull() ?: 0L
                // The muxer may reserve MP4 metadata before samples: file bytes never prove recorded frames.
                if (size > 4096L && alive()) { outputBytes = true; break }
                SystemClock.sleep(250)
            }
            check(outputBytes && alive()) { "No live recorder with file output within ten seconds" }
            SystemClock.sleep(1000) // genuine preroll before the action; never accelerate captured UI
            check(alive()) { "Recorder exited during preroll" }
        }
        notes.append("Recorder preroll: ").append(ready.exceptionOrNull()?.toString() ?: "file output observed, one second held; frames unverified").append('\n')
        var actionPassed = false
        try {
            ready.getOrThrow()
            action() // Real touch actions only, after a verified live recorder/preroll.
            actionPassed = true
            SystemClock.sleep(1200) // hold the genuine completed UI in the recording
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
    @Test fun a_firstBlackTyping() {
        prepareNotes(KeyColour.BLACK)
        recording("01-black-typing") {
            typeText("Make room for good ideas", paced = true)
            expectText("Make room for good ideas")
            capture("01-black-typing-final")
        }
        recording("02-black-trackpad-cursor") {
            repeat(3) {
                val before=selection();dragPadLeft(false)
                waitFor("trackpad moves actual editor cursor") { selection()==((before.first-2).coerceAtLeast(0) to (before.second-2).coerceAtLeast(0)) }
                SystemClock.sleep(500)
            }
            capture("02-black-cursor-final")
        }
        recording("03-black-shift-selection") {
            dragPadLeft(true)
            waitFor("selection visible") { selection().let { (a,b) -> b > a } }
            capture("03-black-selection-final")
        }
    }
}
