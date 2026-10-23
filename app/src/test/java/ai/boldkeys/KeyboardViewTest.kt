package ai.boldkeys

import android.app.Activity
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.android.controller.ActivityController
import java.time.Duration

/** Exercises real key/picker hit targets and delayed touch handling, without a device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class KeyboardViewTest {
    private lateinit var activity: Activity
    private lateinit var activityController: ActivityController<Activity>
    private lateinit var keyboard: KeyboardView
    private val typed = mutableListOf<String>()
    private val shortcuts = mutableListOf<Char>()
    private var deletes = 0

    @Before fun setUp() {
        activityController = Robolectric.buildActivity(Activity::class.java).setup()
        activity = activityController.get()
        activity.getSharedPreferences("bk", 0).edit().clear().putString("pattern", "off").apply()
        keyboard = KeyboardView(activity, Feedback(activity), object : KeyboardView.Listener {
            override fun onText(text: String) { typed += text }
            override fun onDelete() { deletes++ }
            override fun onEnter() {}
            override fun onShortcut(letter: Char): Boolean { shortcuts += letter; return true }
        })
        activity.setContentView(keyboard)
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        keyboard.layout(0, 0, 400, keyboard.measuredHeight)
        advance(1000)
    }

    @After fun tearDown() { activityController.pause().stop().destroy() }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun key(row: Int, column: Int): RectF {
        val cells = field(keyboard, "cells") as List<*>
        val cell = (cells[row] as List<*>)[column]!!
        return field(cell, "rect") as RectF
    }
    private fun pickerRect(name: String): RectF {
        val geo = KeyboardView::class.java.getDeclaredMethod("pickGeo").apply { isAccessible = true }.invoke(keyboard)!!
        return field(geo, name) as RectF
    }
    private fun event(action: Int, rect: RectF) {
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, rect.centerX(), rect.centerY(), 0).let {
            keyboard.onTouchEvent(it); it.recycle()
        }
    }
    private fun tap(rect: RectF) { event(MotionEvent.ACTION_DOWN, rect); event(MotionEvent.ACTION_UP, rect) }
    private fun multi(action: Int, first: RectF, second: RectF) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }, MotionEvent.PointerProperties().apply { id = 1 })
        val coords = arrayOf(first, second).map { r -> MotionEvent.PointerCoords().apply { x = r.centerX(); y = r.centerY(); pressure = 1f; size = 1f } }.toTypedArray()
        val now = SystemClock.uptimeMillis()
        MotionEvent.obtain(now, now, action, 2, props, coords, 0, 0, 1f, 1f, 0, 0, 0, 0).let { keyboard.onTouchEvent(it); it.recycle() }
    }
    private val shift get() = key(3, 0)
    private val speed get() = key(3, 4)
    private val q get() = key(0, 0)

    @Test fun shortShiftStillCapitalizesOnlyOneLetter() {
        tap(shift)
        assertEquals(KeyboardView.Shift.ONCE, keyboard.shift)
        tap(q); tap(q)
        assertEquals(listOf("Q", "q"), typed)
    }

    @Test fun doubleTapStillLocksAndTapUnlocks() {
        tap(shift); advance(100); tap(shift)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        tap(q); tap(q)
        assertEquals(listOf("Q", "Q"), typed)
        tap(shift)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
    }

    @Test fun holdLocksAtThresholdAndSurvivesTypingAndAutoCapsRefresh() {
        event(MotionEvent.ACTION_DOWN, shift)
        advance(449)
        assertEquals(KeyboardView.Shift.ONCE, keyboard.shift)
        advance(1)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_UP, shift)
        keyboard.setAutoShift(false)
        tap(q); tap(q)
        assertEquals(listOf("Q", "Q"), typed)
        tap(shift); tap(q)
        assertEquals("q", typed.last())
    }

    @Test fun holdAlsoLocksWhenAutomaticCapitalWasAlreadyOn() {
        keyboard.setAutoShift(true)
        event(MotionEvent.ACTION_DOWN, shift)
        advance(450)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_UP, shift)
    }

    @Test fun holdingALockedShiftUnlocksWithoutRelocking() {
        tap(shift); tap(shift)
        event(MotionEvent.ACTION_DOWN, shift)
        advance(1000)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
        event(MotionEvent.ACTION_UP, shift)
    }

    @Test fun releasedCancelledMovedOrDetachedHoldCannotLockLater() {
        event(MotionEvent.ACTION_DOWN, shift); event(MotionEvent.ACTION_UP, shift)
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_DOWN, shift); event(MotionEvent.ACTION_CANCEL, shift)
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_DOWN, shift); event(MotionEvent.ACTION_MOVE, q)
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_UP, q)
        event(MotionEvent.ACTION_DOWN, shift)
        activity.setContentView(View(activity))
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
    }

    @Test fun usingHeldShiftToTypeDoesNotLockIt() {
        event(MotionEvent.ACTION_DOWN, shift)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, q)
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), shift, q)
        advance(500)
        event(MotionEvent.ACTION_UP, shift)
        assertEquals(listOf("Q"), typed)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
    }

    @Test fun selectionAndNewFieldCancelThePendingHold() {
        event(MotionEvent.ACTION_DOWN, shift)
        keyboard.cancelShiftHold() // the service calls this when Shift+trackpad selects text
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_UP, shift)
        event(MotionEvent.ACTION_DOWN, shift)
        keyboard.resetPicker() // onStartInputView for a new field
        advance(500)
        assertNotEquals(KeyboardView.Shift.CAPS, keyboard.shift)
    }

    @Test fun selectionAfterThresholdUndoesOnlyTheCurrentHoldLock() {
        event(MotionEvent.ACTION_DOWN, shift)
        advance(500)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        keyboard.cancelShiftHold()
        event(MotionEvent.ACTION_UP, shift)
        assertEquals(KeyboardView.Shift.ONCE, keyboard.shift)
        advance(500)
        event(MotionEvent.ACTION_DOWN, shift); advance(500); event(MotionEvent.ACTION_UP, shift)
        keyboard.cancelShiftHold() // an already completed hold is not a pending selection gesture
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
    }

    @Test fun rightShiftHoldLocksWithoutTogglingOnReleaseWhenEmojiIsDisabled() {
        activity.getSharedPreferences("bk", 0).edit().putBoolean("emojiPages", false).apply()
        keyboard.applySettings()
        event(MotionEvent.ACTION_DOWN, speed)
        advance(450)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        event(MotionEvent.ACTION_UP, speed)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        tap(speed)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
        tap(speed); tap(q)
        assertEquals(listOf("Q"), typed)
    }

    @Test fun speedShortcutDoesNotActivateCapsOrEmojiPicker() {
        event(MotionEvent.ACTION_DOWN, speed)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), speed, q)
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), speed, q)
        advance(500)
        event(MotionEvent.ACTION_UP, speed)
        assertEquals(listOf('Q'), shortcuts)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
        assertFalse(keyboard.pickerOpen())
    }

    @Test fun rightShiftShortcutAfterThresholdDoesNotLeaveCapsLocked() {
        activity.getSharedPreferences("bk", 0).edit().putBoolean("emojiPages", false).apply()
        keyboard.applySettings()
        event(MotionEvent.ACTION_DOWN, speed)
        advance(500)
        assertEquals(KeyboardView.Shift.CAPS, keyboard.shift)
        multi(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), speed, q)
        multi(MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), speed, q)
        event(MotionEvent.ACTION_UP, speed)
        assertEquals(listOf('Q'), shortcuts)
        assertEquals(KeyboardView.Shift.OFF, keyboard.shift)
        assertFalse(keyboard.pickerOpen())
    }

    @Test fun symbolsCloseIsLeftMoreIsRightAndPagesWrap() {
        repeat(2) {
            tap(key(3, 3))
            assertTrue(keyboard.pickerOpen())
            val close = pickerRect("close"); val more = pickerRect("more")
            assertTrue(close.right < more.left)
            tap(more); advance(1000)
            assertTrue(keyboard.pickerOpen())
            assertTrue((field(keyboard, "pickScroll") as Float) > 0f)
            tap(more); advance(1000)
            tap(more); advance(1000)
            assertEquals(0f, field(keyboard, "pickScroll") as Float, 0.1f)
            tap(close)
            assertFalse(keyboard.pickerOpen())
        }
        tap(q)
        assertEquals(listOf("q"), typed)
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE) // Emoji.load filters with Paint.hasGlyph; legacy shadows have no emoji font.
    fun emojiCloseMoreAndDeleteHaveIndependentHitTargets() {
        tap(speed)
        assertTrue(keyboard.pickerOpen())
        assertTrue("Native font should provide multiple emoji groups", (field(keyboard, "pickSections") as List<*>).size > 1)
        val close = pickerRect("close"); val more = pickerRect("more"); val del = pickerRect("del")
        assertTrue(close.right < del.left)
        assertTrue(del.right < more.left)
        tap(more); advance(1000)
        assertTrue(keyboard.pickerOpen())
        assertTrue((field(keyboard, "pickScroll") as Float) > 0f)
        tap(del)
        assertEquals(1, deletes)
        assertTrue(keyboard.pickerOpen())
        tap(close)
        assertFalse(keyboard.pickerOpen())
        tap(speed); tap(pickerRect("close"))
        assertFalse(keyboard.pickerOpen())
    }
}
