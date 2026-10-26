package ai.boldkeys

import android.content.Intent
import android.content.res.Configuration
import android.graphics.PointF
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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

/** Real Android window/input-connection tests. No fake keyboard rendering or injected text commits. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ColourUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
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
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
        device.setOrientationNatural()
        device.executeShellCommand("cmd uimode night no")
        device.executeShellCommand("settings put system font_scale 1.0")
        device.executeShellCommand("ime enable ai.boldkeys/.BoldKeysService")
        device.executeShellCommand("ime set ai.boldkeys/.BoldKeysService")
        waitFor("Bold Keys selected") {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == "ai.boldkeys/.BoldKeysService"
        }
    }

    @After fun restoreDisplay() {
        runCatching { device.setOrientationNatural() }
        runCatching { device.executeShellCommand("cmd uimode night no") }
        runCatching { device.executeShellCommand("settings put system font_scale 1.0") }
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
    private fun awaitIme() = waitFor("visible, laid-out real IME") {
        val views = imeViews()
        val kb = views.filterIsInstance<KeyboardView>().singleOrNull()
        val strip = views.filterIsInstance<SuggestionBar>().singleOrNull()
        kb != null && strip != null && main {
            val location = IntArray(2); kb.getLocationOnScreen(location)
            kb.width >= device.displayWidth * 0.8f && location[0] >= 0 && location[1] >= 0 &&
                location[0] + kb.width <= device.displayWidth && location[1] + kb.height <= device.displayHeight
        }
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
        val point = keyPoint(row, column)
        assertTrue("Key must be on screen: $row/$column ($point)", point.x in 0f..device.displayWidth.toFloat() && point.y in 0f..device.displayHeight.toFloat())
        assertTrue("Key tap injected", device.click(point.x.toInt(), point.y.toInt()))
    }
    private fun capture(name: String) {
        instrumentation.waitForIdleSync()
        assertTrue("Screenshot $name", device.takeScreenshot(File(output, "$name.png")))
        device.dumpWindowHierarchy(File(output, "$name.xml"))
        mirrorEvidence()
    }
    private fun mirrorEvidence() {
        // Preserve evidence independently of UTP's optional test-app cleanup.
        val result = runCatching {
            device.executeShellCommand("mkdir -p /sdcard/Download/bold-keys-qa/evidence && cp -R ${output.absolutePath}/. /sdcard/Download/bold-keys-qa/evidence/")
        }
        if (result.isFailure || !result.getOrDefault("").isBlank()) {
            File(output, "evidence-copy-warning.txt").writeText(result.exceptionOrNull()?.stackTraceToString() ?: result.getOrDefault(""))
        }
    }

    private fun launchSettings() {
        instrumentation.startActivitySync(Intent(context, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitFor("settings home") { device.findObject(By.text("Bold Keys")) != null || device.findObject(By.text("Try it")) != null }
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
    private fun touch(action: Int, positions: List<PointF>, downAt: Long) {
        val properties = positions.indices.map { MotionEvent.PointerProperties().apply { id = it; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
        val coordinates = positions.map { p -> MotionEvent.PointerCoords().apply { x = p.x; y = p.y; pressure = 1f; size = 1f } }.toTypedArray()
        val event = MotionEvent.obtain(downAt, SystemClock.uptimeMillis(), action, positions.size, properties, coordinates, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { assertTrue("Real touchscreen event injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }
    private fun dragPadLeft(select: Boolean) {
        val pad = padPoint()
        val distance = main { bar().resources.displayMetrics.density } * 28f // exactly two 13dp cursor steps
        val end = PointF(pad.x - distance, pad.y)
        val down = SystemClock.uptimeMillis()
        if (select) {
            val shift = keyPoint(3, 0)
            touch(MotionEvent.ACTION_DOWN, listOf(shift), down)
            touch(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, pad), down)
            touch(MotionEvent.ACTION_MOVE, listOf(shift, end), down)
            touch(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(shift, end), down)
            touch(MotionEvent.ACTION_UP, listOf(shift), down)
        } else {
            touch(MotionEvent.ACTION_DOWN, listOf(pad), down)
            touch(MotionEvent.ACTION_MOVE, listOf(end), down)
            touch(MotionEvent.ACTION_UP, listOf(end), down)
        }
    }
    private fun pickerPoint(name: String): PointF = main {
        val kb = keyboard()
        val geometry = KeyboardView::class.java.getDeclaredMethod("pickGeo").apply { isAccessible = true }.invoke(kb)!!
        point(kb, field(geometry, name) as RectF)
    }
    private fun tapPicker(name: String) { val point = pickerPoint(name); assertTrue(device.click(point.x.toInt(), point.y.toInt())) }
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

    /** Optional raw footage. Failures are documented separately and never count as a successful capture. */
    private fun recording(name: String, action: () -> Unit) {
        val dir = "/sdcard/Download/bold-keys-qa"
        device.executeShellCommand("mkdir -p $dir")
        val path = "$dir/$name.mp4"
        val pid = device.executeShellCommand("screenrecord --bit-rate 6000000 --time-limit 60 $path >$dir/$name.log 2>&1 & echo \$!").trim()
        val started = pid.matches(Regex("[0-9]+"))
        try {
            action()
            SystemClock.sleep(1200) // hold the genuine completed UI in the recording
        } finally {
            if (started) device.executeShellCommand("kill -INT $pid")
            val status = device.executeShellCommand("ls -l $path 2>&1")
            File(output, "recording-$name.txt").writeText("Raw recording path: $path\nRecorder PID returned: $started\n$status\nVerify decoding before using; UI tests do not certify video encoding.\n")
        }
    }

    @Test fun a_coloursSelectPersistAndKeepRealTypingAndTrackpad() {
        // A real settings selection isolates the materials from the emulator's light sensor.
        openAppearance()
        textOnScreen("Off").click()
        waitFor("backlight off for material captures") { Prefs.backlightMode(context) == "off" }
        for (colour in palettes) {
            choose(colour.id, colour.label)
            capture("chooser-${colour.id}-portrait-day")
            // A newly created settings Activity must read the saved choice, not remembered Compose state.
            openAppearance()
            assertEquals(colour.id, Prefs.theme(context))
            assertTrue("Persisted selected label", selected(textOnScreen(colour.label)))
            launchHost()
            assertPalette(colour)
            tap(0, 0); expectText("q")
            tap(3, 0); tap(0, 1); expectText("qW")
            tap(0, 2); expectText("qWe")
            tap(1, 9); expectText("qW")
            tap(0, 3); expectText("qWr")
            waitFor("cursor after typing") { selection() == (3 to 3) }
            dragPadLeft(false)
            waitFor("trackpad moved cursor two characters") { selection() == (1 to 1) }
            tap(0, 4); expectText("qtWr")
            dragPadLeft(true)
            waitFor("Shift plus trackpad selected two characters") { selection().let { (start, end) -> end - start == 2 } }
            tap(1, 9); expectText("Wr")
            capture("qa-${colour.id}-portrait-editor")
            repeat(2) {
                tap(3, 3); waitFor("symbol picker") { pickerOpen() }
                tapPicker("more"); assertTrue(pickerOpen())
                tapPicker("close"); waitFor("symbol picker closed") { !pickerOpen() }
                tap(3, 4); waitFor("emoji picker") { pickerOpen() }
                tapPicker("close"); waitFor("emoji picker closed") { !pickerOpen() }
            }
            realTryField()
            assertPalette(colour)
            typeText("Make it yours.")
            waitFor("real TryCard typing") { device.findObject(By.text("Make it yours.")) != null }
            capture("palette-${colour.id}-portrait-real-app")
        }
        choose("auto", "Match phone")
        launchHost(); assertPalette(KeyColour.WHITE)
        capture("palette-auto-portrait-day")
    }

    @Test fun b_systemAppearanceAndLandscapePreserveExplicitColours() {
        for (colour in listOf(KeyColour.RED, KeyColour.WHITE_GOLD)) {
            choose(colour.id, colour.label)
            for (night in listOf(false, true)) {
                device.executeShellCommand("cmd uimode night ${if (night) "yes" else "no"}")
                launchHost()
                waitFor("system appearance") { main { context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO } }
                assertPalette(colour)
                tap(0, 0); expectText("q")
                capture("qa-${colour.id}-portrait-${if (night) "night" else "day"}")
                device.setOrientationLeft()
                waitFor("landscape display") { device.displayWidth > device.displayHeight }
                awaitIme()
                assertPalette(colour)
                tap(0, 1); expectText("qw")
                val bottomKey = keyPoint(3, 2)
                assertTrue("Bottom row clear of navigation edge", bottomKey.y < device.displayHeight - 2)
                capture("qa-${colour.id}-landscape-${if (night) "night" else "day"}")
                device.setOrientationNatural()
                waitFor("portrait display") { device.displayHeight > device.displayWidth }
            }
        }
        choose("auto", "Match phone")
        for (night in listOf(true, false)) {
            device.executeShellCommand("cmd uimode night ${if (night) "yes" else "no"}")
            launchHost()
            waitFor("automatic palette") { runCatching { main { (field(keyboard(), "th") as KeyTheme).colour == if (night) KeyColour.BLACK else KeyColour.WHITE } }.getOrDefault(false) }
            assertPalette(if (night) KeyColour.BLACK else KeyColour.WHITE)
            capture("palette-auto-portrait-${if (night) "night" else "day"}")
        }
    }

    @Test fun c_privateAndNumberFieldsAndPickerReset() {
        choose(KeyColour.RED.id, KeyColour.RED.label)
        for (mode in listOf("text", "search", "email", "url", "password", "number")) {
            launchHost(mode)
            assertPalette(KeyColour.RED)
            assertFalse("A new field resets the picker", pickerOpen())
            if (mode == "number") {
                tap(3, 1); expectText("0")
                tap(2, 0); tap(0, 1); expectText("01")
            } else {
                typeText("teh")
                if (mode in listOf("email", "url", "password")) {
                    tap(3, 2); tap(3, 2); expectText("teh  ")
                } else expectText("teh")
                if (mode in listOf("email", "password")) {
                    assertTrue("Private fields must not offer suggestions", main { (field(bar(), "words") as List<*>).isEmpty() })
                }
            }
            capture("qa-input-$mode")
            tap(3, 3); waitFor("picker before switching field") { pickerOpen() }
        }
        launchHost("text")
        assertFalse(pickerOpen())
        tap(0, 0); expectText("q")
    }

    @Test fun d_largeTextBacklightIndependenceAndRawFootage() {
        device.executeShellCommand("settings put system font_scale 1.3")
        waitFor("larger system text") { context.resources.configuration.fontScale >= 1.25f }
        choose(KeyColour.WHITE_GOLD.id, KeyColour.WHITE_GOLD.label)
        capture("chooser-white_gold-large-text")
        choose(KeyColour.RED.id, KeyColour.RED.label)
        capture("chooser-red-large-text")
        device.executeShellCommand("settings put system font_scale 1.0")
        openAppearance()
        textOnScreen("On").click()
        waitFor("backlight on") { Prefs.backlightMode(context) == "on" }
        for (colour in listOf(KeyColour.RED, KeyColour.WHITE_GOLD)) {
            choose(colour.id, colour.label)
            assertEquals("Changing colour preserves backlight mode", "on", Prefs.backlightMode(context))
            launchHost()
            assertPalette(colour)
            val glow = main { field(keyboard(), "glow") as Float }
            if (colour.dark) assertTrue("Dark keys retain backlight", glow > 0f) else assertEquals("Light keys remain unlit", 0f, glow, 0f)
            capture("qa-${colour.id}-backlight-on")
        }
        openAppearance()
        textOnScreen("Off").click()
        waitFor("backlight off") { Prefs.backlightMode(context) == "off" }
        openAppearance()
        recording("chooser-red") {
            textOnScreen("Red").click()
            waitFor("Red selected for capture") { Prefs.theme(context) == "red" }
            capture("chooser-red-final")
        }
        realTryField()
        assertPalette(KeyColour.RED)
        recording("typing-red") {
            typeText("Make it yours.", paced = true)
            waitFor("recorded typing") { device.findObject(By.text("Make it yours.")) != null }
            capture("palette-red-final-real-app")
        }
    }
}
