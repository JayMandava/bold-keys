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
        assertEquals(KeyColour.entries.map { it.id }.toSet() + "auto", KeyColour.choices.map { it.first }.toSet())
        assertEquals(KeyColour.entries.size + 1, KeyColour.choices.size)
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

    @Test fun blackAndGoldKeepsTheOriginalPalette() {
        val theme = KeyTheme(KeyColour.BLACK_GOLD)
        assertEquals(0xFF14120F.toInt(), theme.bg)
        assertArrayEquals(floatArrayOf(24f, 24f, 24f), theme.base, 0f)
        assertEquals(0xFFF6F0E1.toInt(), theme.legend)
        assertEquals(0xFFF0DCA6.toInt(), theme.ringTop)
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
        for (colour in listOf(KeyColour.RED, KeyColour.BLACK_GOLD, KeyColour.WHITE_GOLD, KeyColour.OCEAN_DRIVE)) {
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
    fun appearancePreviewShowsTheCardSurfaceOutsideTheKeyboardOutline() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.put(context, "backlight", "on")
        Prefs.put(context, "backlightLevel", 1f)
        val listener = object : KeyboardView.Listener {
            override fun onText(text: String) = Unit
            override fun onDelete() = Unit
            override fun onEnter() = Unit
        }
        for (colour in KeyColour.entries) {
            Prefs.put(context, "theme", colour.id)
            for (preview in listOf(false, true)) {
                val keyboard = KeyboardView(context, Feedback(context), listener, preview = preview)
                keyboard.applySettings()
                layout(keyboard, 304)
                val bitmap = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
                keyboard.draw(Canvas(bitmap))
                val background = KeyTheme(colour).bg
                assertEquals(background, bitmap.getPixel(keyboard.width / 2, 0))
                assertEquals("The left top corner has an open edge", background, bitmap.getPixel(0, 0))
                assertEquals("The right top corner has an open edge", background, bitmap.getPixel(keyboard.width - 1, 0))
                val corner = if (preview) Color.TRANSPARENT else Color.BLACK
                assertEquals(corner, bitmap.getPixel(0, keyboard.height - 1))
                assertEquals(corner, bitmap.getPixel(keyboard.width - 1, keyboard.height - 1))
                if (preview && !KeyTheme(colour).classic) {
                    for (y in keyboard.height - 16 until keyboard.height) for (x in 0..4) {
                        val left = Color.alpha(bitmap.getPixel(x, y))
                        val right = Color.alpha(bitmap.getPixel(keyboard.width - 1 - x, y))
                        // Skia can round fractional edge coverage differently on a mirrored path.
                        assertTrue("${colour.id} bottom corners are symmetric at $x,$y ($left and $right)", kotlin.math.abs(left - right) <= 8)
                    }
                }
            }
        }
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
                val theme = Prefs.keyTheme(activity)
                bar.applyTheme(theme)
                layout(keyboard, width); layout(bar, width)
                val bmp = Bitmap.createBitmap(width, keyboard.height + bar.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                bar.draw(canvas)
                canvas.save(); canvas.translate(0f, bar.height.toFloat())
                // ViewGroup clips each child. drawColor ignores translation without this clip.
                canvas.clipRect(0, 0, width, keyboard.height); keyboard.draw(canvas); canvas.restore()
                assertEquals("The suggestion strip uses the theme's surface", theme.bg, bmp.getPixel(1, 1))
                assertNotEquals("Trackpad must render above the keys", theme.bg, bmp.getPixel(width / 2, bar.height / 2))
                for (x in listOf(width / 4, width / 2, width * 3 / 4)) {
                    for (y in bar.height - 1..bar.height + 1) {
                        assertEquals("${colour.id}: the suggestion strip meets the keys without a colour break", theme.bg, bmp.getPixel(x, y))
                    }
                }
                if (colour == KeyColour.OCEAN_DRIVE) {
                    for (x in listOf(width / 2 - 26, width / 2 + 26)) {
                        assertEquals("Ocean Drive has a clean suggestion surface beside the trackpad", theme.bg, bmp.getPixel(x, bar.height / 2))
                    }
                }
                val ridgeY = bar.height + (key(keyboard, 3, 2).bottom + 5.5f).toInt()
                if (colour == KeyColour.BLACK) {
                    assertNotEquals("Black retains its original bottom separator", theme.bg, bmp.getPixel(width / 2, ridgeY))
                } else if (colour == KeyColour.BLACK_GOLD) {
                    assertEquals("Black & Gold fills the base below the keys", theme.bodyBottom, bmp.getPixel(width / 2, bmp.height - 3))
                    for (y in ridgeY - 1..ridgeY + 1) {
                        val pixel = bmp.getPixel(width / 2, y)
                        assertTrue("Black & Gold keeps the base free of a gold rim", Color.red(pixel) <= Color.red(theme.bodyBottom))
                    }
                } else {
                    for (y in ridgeY - 1..ridgeY + 1) assertEquals("${colour.id} has a continuous base under the keys", theme.bg, bmp.getPixel(width / 2, y))
                }
                val middleOfKey = bar.height + key(keyboard, 0, 0).centerY().toInt()
                assertEquals("${colour.id} has no left border", theme.bg, bmp.getPixel(0, middleOfKey))
                assertEquals("${colour.id} has no right border", theme.bg, bmp.getPixel(width - 1, middleOfKey))
                File(output, "${colour.id}-$width.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                val pixels = IntArray(width * bmp.height); bmp.getPixels(pixels, 0, width, 0, 0, width, bmp.height)
                images += pixels.contentHashCode()
                assertEquals(colour, (field(keyboard, "th") as KeyTheme).colour)
                assertEquals(colour, (field(bar, "th") as KeyTheme).colour)
                tap(keyboard, key(keyboard, 0, 0)); assertEquals("q", typed.last())
                tap(keyboard, key(keyboard, 2, 0))
                tap(keyboard, key(keyboard, 1, 3)); assertEquals("6", typed.last())
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
            assertEquals("Each palette and orientation must produce a distinct rendering", 2 * KeyColour.entries.size, images.size)
        } finally { controller.pause().stop().destroy() }
    }
}
