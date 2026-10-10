package ai.boldkeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The keyboard itself, drawn by hand so every key can look like a raised metal-and-plastic key: a gradient cap with a bright top edge,
 * a dark underside, thin gaps, and a silver bar between the rows. Keys act the moment a finger lands (like a real keyboard) and several
 * fingers can type at once, so quick typing rolls from key to key without losing letters.
 */
class KeyboardView(context: Context, private val feedback: Feedback, private val listener: Listener) : View(context) {
    interface Listener {
        fun onText(text: String)
        fun onDelete()
        fun onEnter()
    }

    enum class Shift { OFF, ONCE, CAPS }

    private class Cell(val key: Key, val row: Int, val rect: RectF) {
        var cap: LinearGradient? = null
        var capPressed: LinearGradient? = null
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val keyH get() = dp(50f)
    private val gap get() = dp(3f)
    private val rowGap get() = dp(4f)
    private val padX get() = dp(4f)
    private val padY get() = dp(7f)

    var shift = Shift.OFF
        private set
    private var altLayer = false
    private var lastShiftTap = 0L
    private var cells: List<List<Cell>> = emptyList()
    private val pressed = HashMap<Int, Cell>()
    private val ui = Handler(Looper.getMainLooper())

    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val chromePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (padY * 2 + keyH * 4 + rowGap * 3).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rows() = if (altLayer) Layouts.symbolRows else Layouts.letterRows

    private fun rebuild() {
        if (width == 0) return
        val unit = (width - padX * 2) / 10f
        cells = rows().mapIndexed { r, row ->
            var x = padX
            val top = padY + r * (keyH + rowGap)
            row.map { k ->
                val w = unit * k.weight
                val rect = RectF(x + gap / 2, top, x + w - gap / 2, top + keyH)
                x += w
                Cell(k, r, rect).also { c ->
                    val special = k.kind != Kind.CHAR && k.kind != Kind.SPACE
                    val (t, b) = if (special) 0xFF40444a.toInt() to 0xFF202225.toInt() else 0xFF565a60.toInt() to 0xFF2c2f33.toInt()
                    c.cap = LinearGradient(0f, rect.top, 0f, rect.bottom, t, b, Shader.TileMode.CLAMP)
                    c.capPressed = LinearGradient(0f, rect.top, 0f, rect.bottom, 0xFF17181a.toInt(), 0xFF2d3035.toInt(), Shader.TileMode.CLAMP)
                }
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0b0b0c.toInt())
        // the silver bars between the rows
        val rowsN = cells.size
        for (r in 0 until rowsN - 1) {
            val y = padY + (r + 1) * (keyH + rowGap) - rowGap / 2
            chromePaint.shader = LinearGradient(0f, y - dp(1f), 0f, y + dp(1f), 0xFFc9cdd2.toInt(), 0xFF5d6168.toInt(), Shader.TileMode.CLAMP)
            canvas.drawRect(padX, y - dp(0.8f), width - padX, y + dp(0.8f), chromePaint)
        }
        chromePaint.shader = null
        val down = pressed.values.toSet()
        for (row in cells) for (c in row) drawKey(canvas, c, c in down)
    }

    private fun drawKey(canvas: Canvas, c: Cell, isDown: Boolean) {
        val r = c.rect
        val rad = dp(6f)
        capPaint.shader = if (isDown) c.capPressed else c.cap
        canvas.drawRoundRect(r, rad, rad, capPaint)
        // bright top edge and dark underside
        edgePaint.color = if (isDown) 0x33ffffff else 0x77ffffff
        canvas.drawLine(r.left + rad, r.top + dp(0.6f), r.right - rad, r.top + dp(0.6f), edgePaint)
        edgePaint.color = 0xAA000000.toInt()
        canvas.drawLine(r.left + rad, r.bottom - dp(0.5f), r.right - rad, r.bottom - dp(0.5f), edgePaint)

        val dy = if (isDown) dp(1f) else 0f
        val cx = r.centerX()
        val cy = r.centerY() + dy
        when (c.key.kind) {
            Kind.SHIFT -> drawShift(canvas, cx, cy)
            Kind.DELETE -> drawDelete(canvas, cx, cy)
            Kind.ENTER -> drawEnter(canvas, cx, cy)
            Kind.SPACE -> Unit
            else -> {
                val special = c.key.kind == Kind.LAYER
                textPaint.textSize = if (special) dp(15f) else dp(21f)
                textPaint.color = when {
                    special -> 0xFFffb259.toInt() // the Alt orange
                    altLayer && !c.key.label[0].isLetter() -> 0xFFffb259.toInt()
                    else -> 0xFFf1f3f4.toInt()
                }
                val label = if (!altLayer && c.key.kind == Kind.CHAR && c.key.label.length == 1 && c.key.label[0].isLetter() && shift == Shift.OFF) c.key.label else c.key.label
                canvas.drawText(label, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
        }
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val s = dp(9f)
        val p = Path().apply {
            moveTo(cx, cy - s); lineTo(cx + s, cy); lineTo(cx + s * 0.45f, cy); lineTo(cx + s * 0.45f, cy + s * 0.85f)
            lineTo(cx - s * 0.45f, cy + s * 0.85f); lineTo(cx - s * 0.45f, cy); lineTo(cx - s, cy); close()
        }
        iconPaint.color = if (shift == Shift.OFF) 0xFFe6e8ea.toInt() else 0xFFffb259.toInt()
        iconPaint.style = if (shift == Shift.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        canvas.drawPath(p, iconPaint)
        if (shift == Shift.CAPS) canvas.drawLine(cx - s * 0.7f, cy + s * 1.2f, cx + s * 0.7f, cy + s * 1.2f, iconPaint)
        iconPaint.style = Paint.Style.STROKE
    }

    private fun drawDelete(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(11f); val h = dp(8f)
        val p = Path().apply {
            moveTo(cx - w, cy); lineTo(cx - w * 0.45f, cy - h); lineTo(cx + w, cy - h); lineTo(cx + w, cy + h); lineTo(cx - w * 0.45f, cy + h); close()
        }
        iconPaint.color = 0xFFe6e8ea.toInt()
        canvas.drawPath(p, iconPaint)
        val k = dp(3.5f)
        canvas.drawLine(cx - k + dp(1.5f), cy - k, cx + k + dp(1.5f), cy + k, iconPaint)
        canvas.drawLine(cx - k + dp(1.5f), cy + k, cx + k + dp(1.5f), cy - k, iconPaint)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(10f); val h = dp(7f)
        iconPaint.color = 0xFFe6e8ea.toInt()
        val p = Path().apply {
            moveTo(cx + w, cy - h); lineTo(cx + w, cy + h * 0.4f); lineTo(cx - w, cy + h * 0.4f)
            moveTo(cx - w + dp(5f), cy + h * 0.4f - dp(5f)); lineTo(cx - w, cy + h * 0.4f); lineTo(cx - w + dp(5f), cy + h * 0.4f + dp(5f))
        }
        canvas.drawPath(p, iconPaint)
    }

    // ---- touch: act on press, several fingers at once

    private fun hit(x: Float, y: Float): Cell? {
        if (cells.isEmpty()) return null
        val r = ((y - padY) / (keyH + rowGap)).toInt().coerceIn(0, cells.size - 1)
        // nearest key in the row by horizontal distance: there are no dead zones between keys
        return cells[r].minByOrNull { c -> if (x in c.rect.left - gap / 2..c.rect.right + gap / 2) 0f else minOf(abs(x - c.rect.left), abs(x - c.rect.right)) }
    }

    private val repeatDelete = object : Runnable {
        override fun run() {
            if (pressed.values.none { it.key.kind == Kind.DELETE }) return
            feedback.key(); listener.onDelete(); ui.postDelayed(this, 55)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                hit(e.getX(i), e.getY(i))?.let { c ->
                    pressed[e.getPointerId(i)] = c
                    feedback.key(c.key.kind == Kind.SPACE)
                    fire(c.key)
                    if (c.key.kind == Kind.DELETE) ui.postDelayed(repeatDelete, 380)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                pressed.remove(e.getPointerId(e.actionIndex))
                if (pressed.values.none { it.key.kind == Kind.DELETE }) ui.removeCallbacks(repeatDelete)
                invalidate()
            }
        }
        return true
    }

    private fun fire(key: Key) {
        when (key.kind) {
            Kind.CHAR, Kind.SPACE -> {
                var t = key.text
                val isLetter = key.kind == Kind.CHAR && !altLayer && t.length == 1 && t[0].isLetter()
                if (isLetter && shift != Shift.OFF) t = t.uppercase()
                if (isLetter && shift == Shift.ONCE) shift = Shift.OFF
                listener.onText(t)
            }
            Kind.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                shift = when {
                    now - lastShiftTap < 400 && shift == Shift.ONCE -> Shift.CAPS
                    shift == Shift.OFF -> Shift.ONCE
                    else -> Shift.OFF
                }
                lastShiftTap = now
            }
            Kind.DELETE -> listener.onDelete()
            Kind.ENTER -> listener.onEnter()
            Kind.LAYER -> { altLayer = !altLayer; rebuild() }
        }
        invalidate()
    }

    /** Called after text changes: capitalise the next letter at the start of a sentence. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.CAPS) return
        val want = if (on) Shift.ONCE else Shift.OFF
        if (shift != want) { shift = want; invalidate() }
    }
}
