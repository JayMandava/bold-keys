package ai.boldkeys

import android.app.Activity
import android.graphics.RectF
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

/** Actual product views and Android pointer splitting; not a substitute for device input resampling QA. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class TrackpadGestureDispatchTest {
    private lateinit var controller: ActivityController<Activity>
    private lateinit var root: LinearLayout
    private lateinit var keys: KeyboardView
    private lateinit var strip: SuggestionBar
    private data class Move(val dx: Int, val dy: Int, val select: Boolean)
    private val moves = mutableListOf<Move>()
    private var picks = 0

    @Before fun setUp() {
        controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.getSharedPreferences("bk", 0).edit().clear().putString("pattern", "off").apply()
        val feedback = Feedback(activity)
        keys = KeyboardView(activity, feedback, object : KeyboardView.Listener {
            override fun onText(text: String) {}
            override fun onDelete() {}
            override fun onEnter() {}
            override fun onShortcut(letter: Char) = false
        })
        strip = SuggestionBar(activity, feedback, object : SuggestionBar.Listener {
            override fun onPick(index: Int) { picks++ }
            override fun onMove(dx: Int, dy: Int, select: Boolean) {
                moves.add(Move(dx, dy, select))
                // Same shift-hold cancellation performed by BoldKeysService.onMove.
                if (select && (dx != 0 || dy != 0)) keys.cancelShiftHold()
            }
        }, keys::isShiftHeld)
        strip.setSuggestions(listOf("one", "two"))
        root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isMotionEventSplittingEnabled = true
            addView(strip, LinearLayout.LayoutParams(-1, -2))
            addView(keys, LinearLayout.LayoutParams(-1, -2))
        }
        activity.setContentView(root)
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, 720, root.measuredHeight)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_000))
    }

    @After fun tearDown() { controller.pause().stop().destroy() }

    private fun shiftRect(): RectF {
        val rows = KeyboardView::class.java.getDeclaredField("cells").apply { isAccessible = true }.get(keys) as List<*>
        val cell = (rows[3] as List<*>)[0]!!
        return RectF(cell.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(cell) as RectF)
            .apply { offset(keys.left.toFloat(), keys.top.toFloat()) }
    }

    private fun dispatch(select: Boolean) {
        val density = strip.resources.displayMetrics.density
        val padX = strip.left + strip.width / 2f
        val padY = strip.top + strip.height / 2f + 2.5f * density
        val shift = shiftRect()
        TrackpadGesturePlan.perform(select, SystemClock::uptimeMillis,
            { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(it)) }) { timed ->
            val sample = timed.sample
            val properties = sample.pointers.map { pointer -> MotionEvent.PointerProperties().apply {
                id = pointer.id; toolType = MotionEvent.TOOL_TYPE_FINGER
            } }.toTypedArray()
            val coordinates = sample.pointers.map { pointer -> MotionEvent.PointerCoords().apply {
                if (pointer.role == TrackpadGesturePlan.Role.SHIFT) { x = shift.centerX(); y = shift.centerY() }
                else { x = padX + sample.padOffsetDp * density; y = padY }
                pressure = 1f; size = 1f
            } }.toTypedArray()
            val action = when (sample.action) {
                TrackpadGesturePlan.Action.DOWN -> MotionEvent.ACTION_DOWN
                TrackpadGesturePlan.Action.POINTER_DOWN -> MotionEvent.ACTION_POINTER_DOWN or (sample.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                TrackpadGesturePlan.Action.MOVE -> MotionEvent.ACTION_MOVE
                TrackpadGesturePlan.Action.POINTER_UP -> MotionEvent.ACTION_POINTER_UP or (sample.actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
                TrackpadGesturePlan.Action.UP -> MotionEvent.ACTION_UP
            }
            val event = MotionEvent.obtain(timed.downTime, timed.eventTime, action, properties.size, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { assertTrue(root.dispatchTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun generatedSinglePointerPathMovesExactlyTwoCharactersWithoutPickingSuggestion() {
        dispatch(false)
        assertEquals(-2, moves.sumOf { it.dx })
        assertEquals(0, moves.sumOf { it.dy })
        assertTrue(moves.all { !it.select })
        assertEquals(0, picks)
    }

    @Test fun generatedTwoPointerPathSelectsExactlyTwoCharactersAndReleasesShift() {
        dispatch(true)
        assertEquals(-2, moves.sumOf { it.dx })
        assertEquals(0, moves.sumOf { it.dy })
        assertTrue(moves.all { it.select })
        assertEquals(0, picks)
        assertFalse(keys.isShiftHeld())
    }
}
