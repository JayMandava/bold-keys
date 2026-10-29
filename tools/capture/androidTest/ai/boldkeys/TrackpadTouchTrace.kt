package ai.boldkeys

import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject

/** Passive diagnostics for the synthetic host only. Call lifecycle methods on the main thread. */
@SuppressLint("ClickableViewAccessibility") // Observers always return false; product views keep their own handling.
internal class TrackpadTouchTrace(
    private val strip: SuggestionBar,
    private val keys: KeyboardView,
    private val editor: EditText,
    theme: String,
    select: Boolean,
) : AutoCloseable {
    private val events = JSONArray()
    private val errors = JSONArray()
    private var truncated = false
    private var previousSelection: Pair<Int, Int>? = null
    private val tree = editor.viewTreeObserver
    private val draw = ViewTreeObserver.OnDrawListener {
        runCatching {
            val current = editor.selectionStart to editor.selectionEnd
            if (current != previousSelection) snapshot("selection-observed-on-draw")
        }.onFailure { diagnosticError(it) }
    }
    private val report = JSONObject().put("theme", theme).put("select", select)
        .put("kind", "synthetic-editor-passive-touch-trace")
        .put("selection_observation", "before delivered touch, on draw changes, and gesture completion; not every intermediate selection callback")

    init {
        check(editor.context is KeyboardQaActivity) { "Only synthetic editor state may be traced" }
        // These two product View classes override onTouchEvent and install NO OnTouchListener.
        // Keep that source invariant: this observer must never replace a product listener.
        strip.setOnTouchListener(listener("strip"))
        keys.setOnTouchListener(listener("keys"))
        tree.addOnDrawListener(draw)
        snapshot("start")
    }

    @Synchronized private fun add(value: JSONObject) {
        if (events.length() < 256) events.put(value) else truncated = true
    }
    @Synchronized private fun diagnosticError(error: Throwable) { errors.put(error.toString()) }

    private fun listener(name: String) = View.OnTouchListener { view, event ->
        runCatching {
            val location = IntArray(2); view.getLocationOnScreen(location)
            val pointers = JSONArray()
            for (index in 0 until event.pointerCount) {
                val history = JSONArray()
                for (sample in 0 until event.historySize) history.put(JSONObject()
                    .put("event_time", event.getHistoricalEventTime(sample))
                    .put("x", event.getHistoricalX(index, sample)).put("y", event.getHistoricalY(index, sample)))
                pointers.put(JSONObject().put("id", event.getPointerId(index))
                    .put("x", event.getX(index)).put("y", event.getY(index))
                    .put("screen_x", location[0] + event.getX(index)).put("screen_y", location[1] + event.getY(index))
                    .put("history", history))
            }
            add(JSONObject().put("kind", "delivered").put("view", name)
                .put("observed_at", SystemClock.uptimeMillis()).put("event_time", event.eventTime)
                .put("down_time", event.downTime).put("action", MotionEvent.actionToString(event.action))
                .put("action_index", event.actionIndex).put("pointers", pointers)
                .put("selection_start_before_dispatch", editor.selectionStart)
                .put("selection_end_before_dispatch", editor.selectionEnd))
        }.onFailure { diagnosticError(it) }
        false // Always deliver the original event to the unchanged product onTouchEvent implementation.
    }

    fun injected(sample: TrackpadGesturePlan.TimedSample, action: Int, positions: List<Pair<Float, Float>>) {
        val pointers = JSONArray()
        sample.sample.pointers.zip(positions).forEach { (pointer, position) ->
            pointers.put(JSONObject().put("id", pointer.id).put("role", pointer.role.name)
                .put("screen_x", position.first).put("screen_y", position.second))
        }
        add(JSONObject().put("kind", "injected").put("event_time", sample.eventTime)
            .put("observed_at", SystemClock.uptimeMillis()).put("down_time", sample.downTime)
            .put("action", MotionEvent.actionToString(action)).put("pointers", pointers))
    }

    fun snapshot(reason: String) {
        previousSelection = editor.selectionStart to editor.selectionEnd
        add(JSONObject().put("kind", "selection").put("reason", reason)
            .put("observed_at", SystemClock.uptimeMillis()).put("text", editor.text.toString())
            .put("selection_start", editor.selectionStart).put("selection_end", editor.selectionEnd))
    }

    override fun close() {
        try { strip.setOnTouchListener(null) } finally {
            try { keys.setOnTouchListener(null) } finally {
                if (tree.isAlive) tree.removeOnDrawListener(draw)
            }
        }
        snapshot("gesture-completed-or-aborted")
    }

    @Synchronized fun json(): String = report.put("events", events).put("diagnostic_errors", errors)
        .put("truncated", truncated).toString(2) + "\n"
}
