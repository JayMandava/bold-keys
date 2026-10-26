package ai.boldkeys

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.pow

/** Palette migration, contrast and real view hit-target/rendering coverage, without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
class KeyThemeTest {
    @Test fun legacyAndUnknownPreferencesKeepTheirMeaning() {
        assertEquals(KeyColour.BLACK, KeyColour.resolve("dark", false))
        assertEquals(KeyColour.WHITE, KeyColour.resolve("light", true))
        assertEquals(KeyColour.BLACK, KeyColour.resolve("auto", true))
        assertEquals(KeyColour.WHITE, KeyColour.resolve("auto", false))
        assertEquals(KeyColour.BLACK, KeyColour.resolve("missing", false))
        assertEquals(6, KeyColour.choices.size)
        assertEquals(6, KeyColour.choices.map { it.first }.toSet().size)
        val c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("bk", 0).edit().clear().commit()
        assertEquals("dark", Prefs.theme(c))
        Prefs.put(c, "theme", "future-or-corrupt-value")
        assertEquals("dark", Prefs.theme(c))
    }

    @Test fun classicPalettesAreUnchanged() {
        val black = KeyTheme(KeyColour.BLACK)
        assertEquals(0xFF000000.toInt(), black.bg)
        assertArrayEquals(floatArrayOf(36f, 44f, 54f), black.base, 0f)
        assertEquals(0xFFF4F6F8.toInt(), black.legend)
        val white = KeyTheme(KeyColour.WHITE)
        assertEquals(0xFFe6e8ec.toInt(), white.bg)
        assertArrayEquals(floatArrayOf(254f, 254f, 255f), white.base, 0f)
        assertEquals(0xFF1b1f24.toInt(), white.legend)
        assertArrayEquals(black.ridgeColours, white.ridgeColours)
        assertEquals(0xFF2F8FE8.toInt(), black.selection)
        assertEquals(0xFF2F8FE8.toInt(), white.selection)
    }

    @Test fun selectionsPersistAcrossContextsAndDoNotFollowSystemUnlessAutomatic() {
        val c = RuntimeEnvironment.getApplication()
        c.getSharedPreferences("bk", 0).edit().clear().commit()
        for (colour in KeyColour.entries) {
            Prefs.put(c, "theme", colour.id)
            for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
                val config = Configuration(c.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
                val recreated = c.createConfigurationContext(config)
                assertEquals(colour.id, Prefs.theme(recreated))
                assertEquals(colour, Prefs.keyTheme(recreated).colour)
                assertEquals(colour.dark, Prefs.isDark(recreated))
            }
        }
        Prefs.put(c, "theme", "auto")
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val config = Configuration(c.resources.configuration).apply { uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night }
            assertEquals(night == Configuration.UI_MODE_NIGHT_YES, Prefs.isDark(c.createConfigurationContext(config)))
        }
    }

    private fun luminance(rgb: Int): Double = listOf(Color.red(rgb), Color.green(rgb), Color.blue(rgb))
        .map { it / 255.0 }.map { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        .let { it[0] * 0.2126 + it[1] * 0.7152 + it[2] * 0.0722 }
    private fun contrast(a: Int, b: Int): Double = listOf(luminance(a), luminance(b)).sorted().let { (it[1] + 0.05) / (it[0] + 0.05) }

    @Test fun heritageLegendsAndSuggestionsHaveReadableContrast() {
        for (colour in listOf(KeyColour.RED, KeyColour.BLACK_GOLD, KeyColour.WHITE_GOLD)) {
            val th = KeyTheme(colour)
            val base = Color.rgb(th.base[0].toInt(), th.base[1].toInt(), th.base[2].toInt())
            assertTrue("${colour.id} legends", contrast(th.legend, base) >= 4.5)
            assertTrue("${colour.id} primary suggestions", contrast(th.barText, th.bg) >= 4.5)
            assertTrue("${colour.id} secondary suggestions", contrast(th.barText2, th.bg) >= 4.5)
            assertTrue("${colour.id} selected accent", contrast(Color.WHITE, th.selection) >= 4.5)
            assertTrue("${colour.id} picker", contrast(th.legend, th.padTop) >= 4.5)
            assertTrue("${colour.id} volume indicator", contrast(th.indicator, if (th.dark) Color.BLACK else Color.WHITE) >= 3.0)
        }
    }

    private fun field(owner: Any, name: String): Any = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)!!
    private fun key(kb: KeyboardView, row: Int, col: Int): RectF =
        field(((field(kb, "cells") as List<*>)[row] as List<*>)[col]!!, "rect") as RectF
    private fun event(view: View, action: Int, x: Float, y: Float) {
        val time = SystemClock.uptimeMillis()
        MotionEvent.obtain(time, time, action, x, y, 0).let { view.onTouchEvent(it); it.recycle() }
    }
    private fun tap(view: View, rect: RectF) {
        event(view, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY())
        event(view, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY())
    }
    private fun layout(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun everyPaletteRendersAndKeepsTypingModifiersDeletePickersAndTrackpad() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            activity.getSharedPreferences("bk", 0).edit().clear().putString("pattern", "off").putString("backlight", "off").commit()
            val typed = mutableListOf<String>()
            var deletes = 0
            val keyboard = KeyboardView(activity, Feedback(activity), object : KeyboardView.Listener {
                override fun onText(text: String) { typed += text }
                override fun onDelete() { deletes++ }
                override fun onEnter() = Unit
            })
            val moves = mutableListOf<Triple<Int, Int, Boolean>>()
            var select = false
            val bar = SuggestionBar(activity, Feedback(activity), object : SuggestionBar.Listener {
                override fun onPick(index: Int) = Unit
                override fun onMove(dx: Int, dy: Int, selecting: Boolean) { moves += Triple(dx, dy, selecting) }
            }, { select })
            bar.setSuggestions(listOf("hello", "world"))
            val root = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(bar); addView(keyboard)
            }
            activity.setContentView(root)
            val output = File("build/reports/colour-previews").apply { mkdirs() }
            val images = mutableSetOf<Int>()
            for (width in listOf(400, 800)) for (colour in KeyColour.entries) {
                Prefs.put(activity, "theme", colour.id)
                keyboard.applySettings(); keyboard.resetPicker()
                bar.applyTheme(Prefs.keyTheme(activity))
                layout(keyboard, width); layout(bar, width)
                val bmp = Bitmap.createBitmap(width, keyboard.height + bar.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                bar.draw(canvas)
                canvas.save(); canvas.translate(0f, bar.height.toFloat())
                // ViewGroup clips each child. drawColor ignores translation without this clip.
                canvas.clipRect(0, 0, width, keyboard.height); keyboard.draw(canvas); canvas.restore()
                assertNotEquals("Trackpad must render above the keys", Prefs.keyTheme(activity).bg, bmp.getPixel(width / 2, bar.height / 2))
                File(output, "${colour.id}-$width.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val pixels = IntArray(width * bmp.height); bmp.getPixels(pixels, 0, width, 0, 0, width, bmp.height)
                images += pixels.contentHashCode()
                assertEquals(colour, (field(keyboard, "th") as KeyTheme).colour)
                assertEquals(colour, (field(bar, "th") as KeyTheme).colour)
                tap(keyboard, key(keyboard, 0, 0)); assertEquals("q", typed.last())
                tap(keyboard, key(keyboard, 3, 0))
                tap(keyboard, key(keyboard, 0, 0)); assertEquals("Q", typed.last())
                tap(keyboard, key(keyboard, 0, 0)); assertEquals("q", typed.last())
                val before = deletes
                tap(keyboard, key(keyboard, 1, 9)); assertEquals(before + 1, deletes)
                tap(keyboard, key(keyboard, 3, 3)); assertTrue(keyboard.pickerOpen())
                keyboard.draw(Canvas(Bitmap.createBitmap(width, keyboard.height, Bitmap.Config.ARGB_8888)))
                keyboard.resetPicker(); assertFalse(keyboard.pickerOpen())
                for (selecting in listOf(false, true)) {
                    select = selecting
                    val start = moves.size
                    event(bar, MotionEvent.ACTION_DOWN, width / 2f, bar.height / 2f)
                    event(bar, MotionEvent.ACTION_MOVE, width / 2f + 30f, bar.height / 2f)
                    event(bar, MotionEvent.ACTION_UP, width / 2f + 30f, bar.height / 2f)
                    assertTrue(moves.size > start)
                    assertEquals(Triple(2, 0, selecting), moves.last())
                }
            }
            assertEquals("Each palette and orientation must produce a distinct rendering", 10, images.size)
        } finally { controller.pause().stop().destroy() }
    }
}
