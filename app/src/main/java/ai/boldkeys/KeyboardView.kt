package ai.boldkeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
 * The keyboard, drawn by hand to look like the Bold 9900's keys: near-black caps with a domed, glossy top and a soft sheen, rows that
 * curve gently like a smile, thin silver ridges between the rows, light condensed legends with the Alt symbol printed small in the corner.
 * Keys act the moment a finger lands and several fingers can type at once, so quick typing never drops a letter.
 */
class KeyboardView(context: Context, private val feedback: Feedback, private val listener: Listener) : View(context) {
    interface Listener {
        fun onText(text: String)
        fun onDelete()
        fun onEnter()
    }

    enum class Shift { OFF, ONCE, CAPS }

    private class Cell(val key: Key, val row: Int, val col: Int, val rect: RectF)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val keyH get() = dp(49f)
    private val gap get() = dp(3f)
    private val rowGap get() = dp(6f)
    private val padX get() = dp(5f)
    private val padY get() = dp(8f)
    private val smile get() = dp(4.5f) // how much lower the middle of a row sits than its ends

    var shift = Shift.OFF
        private set
    private var altLayer = false
    private var lastShiftTap = 0L
    private var cells: List<List<Cell>> = emptyList()
    private val pressed = HashMap<Int, Cell>()
    private val ui = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT; typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (padY * 2 + smile + keyH * 4 + rowGap * 3).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rows() = if (altLayer) Layouts.symbolRows else Layouts.letterRows

    /** Vertical drop of the row at horizontal position x: 0 at the ends, [smile] in the middle. */
    private fun curve(x: Float): Float {
        val u = (x - width / 2f) / (width / 2f)
        return smile * (1f - u * u)
    }

    private fun rebuild() {
        if (width == 0) return
        val unit = (width - padX * 2) / 10f
        cells = rows().mapIndexed { r, row ->
            var x = padX
            val top = padY + r * (keyH + rowGap)
            row.mapIndexed { c, k ->
                val w = unit * k.weight
                val cx = x + w / 2
                val dy = curve(cx)
                val rect = RectF(x + gap / 2, top + dy, x + w - gap / 2, top + dy + keyH)
                x += w
                Cell(k, r, c, rect)
            }
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF08090a.toInt())
        // the silver ridges between the rows, following the same smile as the keys
        for (r in 0 until cells.size - 1) drawRidge(canvas, padY + (r + 1) * (keyH + rowGap) - rowGap / 2)
        val down = pressed.values.toSet()
        for (row in cells) for (c in row) drawKey(canvas, c, c in down)
    }

    private fun drawRidge(canvas: Canvas, y: Float) {
        val th = dp(2.4f)
        val p = Path().apply {
            moveTo(padX, y)
            quadTo(width / 2f, y + smile * 2f, width - padX, y)
        }
        line.strokeWidth = th
        // a dark groove under a bright chrome line
        line.shader = null; line.color = 0xFF000000.toInt()
        canvas.save(); canvas.translate(0f, dp(1.4f)); canvas.drawPath(p, line); canvas.restore()
        line.shader = LinearGradient(0f, y - th, 0f, y + th + smile, intArrayOf(0xFFf4f6f8.toInt(), 0xFFa9adb3.toInt(), 0xFF5b5f66.toInt()), null, Shader.TileMode.CLAMP)
        canvas.drawPath(p, line)
        line.shader = null
    }

    /** A cap with a slightly domed top and a flat base. */
    private fun capPath(r: RectF, rad: Float, bulge: Float) = Path().apply {
        val rb = rad * 0.8f
        moveTo(r.left, r.bottom - rb)
        lineTo(r.left, r.top + rad)
        quadTo(r.left, r.top - bulge * 0.2f, r.left + rad * 1.2f, r.top - bulge * 0.55f)
        quadTo(r.centerX(), r.top - bulge * 1.7f, r.right - rad * 1.2f, r.top - bulge * 0.55f)
        quadTo(r.right, r.top - bulge * 0.2f, r.right, r.top + rad)
        lineTo(r.right, r.bottom - rb)
        quadTo(r.right, r.bottom, r.right - rb, r.bottom)
        lineTo(r.left + rb, r.bottom)
        quadTo(r.left, r.bottom, r.left, r.bottom - rb)
        close()
    }

    private fun drawKey(canvas: Canvas, c: Cell, isDown: Boolean) {
        val r = RectF(c.rect)
        if (isDown) r.offset(0f, dp(1.3f))
        val rad = dp(7f)
        val special = c.key.kind != Kind.CHAR && c.key.kind != Kind.SPACE
        val path = capPath(r, rad, dp(2.2f))

        // the shadow the key casts on the plate under it
        if (!isDown) {
            fill.shader = null; fill.color = 0xFF000000.toInt()
            canvas.save(); canvas.translate(0f, dp(2.2f)); canvas.drawPath(path, fill); canvas.restore()
        }
        // body: near-black gunmetal, a little lighter at the top, darker at the base
        val top = if (isDown) 0xFF1a1b1d.toInt() else if (special) 0xFF35373b.toInt() else 0xFF3c3e43.toInt()
        val bottom = if (isDown) 0xFF26282b.toInt() else if (special) 0xFF141517.toInt() else 0xFF17181a.toInt()
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        if (!isDown) {
            // the soft sheen on the dome
            fill.shader = RadialGradient(r.centerX(), r.top + r.height() * 0.28f, r.width() * 0.75f, 0x4DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(path, fill)
            // a bright lip along the top edge
            line.strokeWidth = dp(1f)
            line.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(0x00FFFFFF, 0xAAFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawPath(Path().apply {
                moveTo(r.left + rad, r.top + dp(0.9f)); quadTo(r.centerX(), r.top - dp(2.6f), r.right - rad, r.top + dp(0.9f))
            }, line)
            line.shader = null
        }
        fill.shader = null

        val cx = r.centerX()
        val cy = r.centerY() + dp(0.5f)
        when (c.key.kind) {
            Kind.SHIFT -> drawShift(canvas, cx, cy)
            Kind.DELETE -> drawWord(canvas, "del", cx, cy, dp(14f))
            Kind.ENTER -> drawEnter(canvas, cx, cy)
            Kind.SPACE -> drawWord(canvas, "space", cx, cy, dp(14f), 0xFFc9ccd1.toInt())
            Kind.LAYER -> drawWord(canvas, "alt", cx, cy, dp(15f), if (altLayer) 0xFFffb259.toInt() else 0xFFe3e5e8.toInt())
            Kind.CHAR -> {
                val isLetter = c.key.label.length == 1 && c.key.label[0].isLetter()
                legend.textSize = dp(22f)
                legend.color = if (altLayer) 0xFFf4f5f6.toInt() else 0xFFeceef0.toInt()
                legend.setShadowLayer(dp(1.2f), 0f, dp(1f), 0xCC000000.toInt())
                val label = if (isLetter && !altLayer && shift == Shift.OFF && false) c.key.label.lowercase() else c.key.label
                canvas.drawText(label, cx, cy - (legend.ascent() + legend.descent()) / 2, legend)
                legend.clearShadowLayer()
                if (!altLayer && isLetter) secondaryFor(c.row, c.col)?.let { s ->
                    small.textSize = dp(10.5f)
                    small.color = 0xFF9a9ea5.toInt()
                    canvas.drawText(s, r.right - dp(5.5f), r.top + dp(13f), small)
                }
            }
        }
    }

    private fun drawWord(canvas: Canvas, w: String, cx: Float, cy: Float, size: Float, color: Int = 0xFFe3e5e8.toInt()) {
        legend.textSize = size
        legend.color = color
        legend.setShadowLayer(dp(1f), 0f, dp(0.8f), 0xCC000000.toInt())
        canvas.drawText(w, cx, cy - (legend.ascent() + legend.descent()) / 2, legend)
        legend.clearShadowLayer()
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float) {
        val s = dp(8.5f)
        val p = Path().apply {
            moveTo(cx, cy - s); lineTo(cx + s, cy); lineTo(cx + s * 0.45f, cy); lineTo(cx + s * 0.45f, cy + s * 0.85f)
            lineTo(cx - s * 0.45f, cy + s * 0.85f); lineTo(cx - s * 0.45f, cy); lineTo(cx - s, cy); close()
        }
        icon.color = if (shift == Shift.OFF) 0xFFe3e5e8.toInt() else 0xFFffb259.toInt()
        icon.style = if (shift == Shift.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        canvas.drawPath(p, icon)
        if (shift == Shift.CAPS) canvas.drawLine(cx - s * 0.7f, cy + s * 1.25f, cx + s * 0.7f, cy + s * 1.25f, icon)
        icon.style = Paint.Style.STROKE
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(10f); val h = dp(7f)
        icon.color = 0xFFe3e5e8.toInt()
        val p = Path().apply {
            moveTo(cx + w, cy - h); lineTo(cx + w, cy + h * 0.4f); lineTo(cx - w, cy + h * 0.4f)
            moveTo(cx - w + dp(5f), cy + h * 0.4f - dp(5f)); lineTo(cx - w, cy + h * 0.4f); lineTo(cx - w + dp(5f), cy + h * 0.4f + dp(5f))
        }
        canvas.drawPath(p, icon)
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
