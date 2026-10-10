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

    private companion object { const val WHITE = 0xFFF4F6F8.toInt() }

    private class Cell(val key: Key, val row: Int, val col: Int, val rect: RectF)

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private val keyH get() = dp(46f)
    private val gap get() = dp(3.5f)
    private val rowGap get() = dp(11f) // wide enough for the silver ridge to sit between the rows without touching a key
    private val padX get() = dp(5f)
    private val padTop get() = rowGap / 2 + dp(3f)
    private val smile get() = dp(5f) // how much lower the middle of a row sits than its ends

    var shift = Shift.OFF
        private set
    private var altLayer = false
    private var lastShiftTap = 0L
    private var cells: List<List<Cell>> = emptyList()
    private val pressed = HashMap<Int, Cell>()
    private val ui = Handler(Looper.getMainLooper())

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    // Nunito (rounded humanist sans), semibold: the closest open font to the Bold's key legends.
    private val face: Typeface = runCatching {
        Typeface.Builder(context.assets, "Nunito.ttf").setFontVariationSettings("'wght' 700").build()
    }.getOrNull() ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
    private val legend = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = face }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face }
    private val leafFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val leafLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1.8f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (padTop + keyH * 4 + rowGap * 3 + rowGap / 2 + dp(3f) + smile).toInt()
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
            val top = padTop + r * (keyH + rowGap)
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
        canvas.drawColor(0xFF0a0a0b.toInt())
        // the silver ridges above, between and below the rows, following the same smile as the keys
        for (r in 0..cells.size) drawRidge(canvas, padTop + r * (keyH + rowGap) - rowGap / 2)
        val down = pressed.values.toSet()
        for (row in cells) for (c in row) drawKey(canvas, c, c in down)
    }

    private fun drawRidge(canvas: Canvas, y: Float) {
        val p = Path().apply {
            moveTo(0f, y)
            quadTo(width / 2f, y + smile * 2f, width.toFloat(), y)
        }
        // a dark groove just under a thin bright chrome line
        line.shader = null
        line.strokeWidth = dp(1.6f); line.color = 0xFF000000.toInt()
        canvas.save(); canvas.translate(0f, dp(1.5f)); canvas.drawPath(p, line); canvas.restore()
        line.strokeWidth = dp(1.5f)
        line.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, intArrayOf(0xFF8d9197.toInt(), 0xFFf2f4f6.toInt(), 0xFFa9adb3.toInt(), 0xFFf2f4f6.toInt(), 0xFF8d9197.toInt()), null, Shader.TileMode.CLAMP)
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

    /** The crescent facet printed on each key: on the right of the keys left of centre and on the left of the others, so they all lean toward the middle. */
    private fun leafPath(r: RectF, onRight: Boolean): Path {
        fun x(f: Float) = if (onRight) r.left + r.width() * f else r.right - r.width() * f
        val w = r.height()
        return Path().apply {
            moveTo(x(0.80f), r.top + w * 0.16f)
            quadTo(x(0.99f), r.top + w * 0.36f, x(0.93f), r.top + w * 0.86f)
            quadTo(x(0.87f), r.top + w * 0.50f, x(0.80f), r.top + w * 0.16f)
            close()
        }
    }

    private fun drawKey(canvas: Canvas, c: Cell, isDown: Boolean) {
        val r = RectF(c.rect)
        if (isDown) r.offset(0f, dp(1.3f))
        val rad = dp(5f)
        val special = c.key.kind != Kind.CHAR && c.key.kind != Kind.SPACE
        val path = capPath(r, rad, dp(0.9f))
        val leftHalf = r.centerX() < width / 2f

        // the thin dark rim that lifts the key off the plate (its depth)
        if (!isDown) {
            fill.shader = null; fill.color = 0xFF000000.toInt()
            canvas.save(); canvas.translate(0f, dp(1.4f)); canvas.drawPath(path, fill); canvas.restore()
        }
        // body: cool blue-black, lighter at the top edge, darker at the base
        val top = if (isDown) 0xFF171c22.toInt() else if (special) 0xFF2a3037.toInt() else 0xFF2f363f.toInt()
        val bottom = if (isDown) 0xFF1d232a.toInt() else if (special) 0xFF11151a.toInt() else 0xFF13171d.toInt()
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawPath(path, fill)
        fill.shader = null
        if (!isDown) {
            // bevel: a bright edge on the top-left, a dark one on the bottom-right
            line.strokeWidth = dp(1f)
            line.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(0x66FFFFFF, 0x10FFFFFF, 0x00000000, 0x99000000.toInt()), floatArrayOf(0f, 0.35f, 0.6f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(path, line)
            line.shader = null
        }
        // the curved facet
        if (c.key.kind != Kind.SPACE) {
            val leaf = leafPath(r, leftHalf)
            leafFill.shader = LinearGradient(r.left, r.top, r.right, r.bottom, if (isDown) 0x08FFFFFF else 0x1CFFFFFF, 0x04FFFFFF, Shader.TileMode.CLAMP)
            canvas.drawPath(leaf, leafFill)
            leafLine.strokeWidth = dp(0.7f)
            leafLine.color = if (isDown) 0x40FFFFFF else 0x88FFFFFF.toInt()
            canvas.drawPath(leaf, leafLine)
        } else if (!isDown) {
            // the space bar's chrome lip: a thin bright crescent along its lower edge
            leafLine.strokeWidth = dp(1.3f)
            leafLine.shader = LinearGradient(r.left, 0f, r.right, 0f, intArrayOf(0x00FFFFFF, 0xDDFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawPath(Path().apply { moveTo(r.left + dp(10f), r.bottom - dp(5f)); quadTo(r.centerX(), r.bottom - dp(1f), r.right - dp(10f), r.bottom - dp(5f)) }, leafLine)
            leafLine.shader = null
        }

        val cx = r.centerX()
        val cy = r.centerY() + dp(1.5f)
        // the legends all share one white; letters lean (bold italic), the small symbols stand upright
        when (c.key.kind) {
            Kind.SHIFT -> drawShift(canvas, cx + (if (leftHalf) -dp(3f) else dp(3f)), cy)
            Kind.DELETE -> drawWord(canvas, "del", cx + dp(-3f), cy, dp(13f))
            Kind.ENTER -> drawEnter(canvas, cx + dp(3f), cy)
            Kind.SPACE -> drawWord(canvas, "space", cx, cy - dp(2f), dp(13f))
            Kind.LAYER -> drawWord(canvas, "alt", cx - dp(5f), cy, dp(13.5f), if (altLayer) 0xFFffb259.toInt() else WHITE)
            Kind.CHAR -> {
                val isLetter = c.key.label.length == 1 && c.key.label[0].isLetter()
                val away = if (leftHalf) -1f else 1f // the side away from the facet
                legend.color = WHITE
                legend.setShadowLayer(dp(0.6f), 0f, dp(0.7f), 0xAA000000.toInt())
                if (isLetter && !altLayer) {
                    legend.textSkewX = -0.24f
                    legend.textSize = dp(16.5f)
                    canvas.drawText(c.key.label, cx + away * dp(5f), cy - (legend.ascent() + legend.descent()) / 2 + dp(3f), legend)
                    legend.textSkewX = 0f
                } else {
                    legend.textSize = dp(19f)
                    canvas.drawText(c.key.label, cx + away * dp(4f), cy - (legend.ascent() + legend.descent()) / 2, legend)
                }
                legend.clearShadowLayer()
                if (!altLayer && isLetter) secondaryFor(c.row, c.col)?.let { sym ->
                    small.textSize = dp(11.5f)
                    small.color = WHITE
                    small.textAlign = if (leftHalf) Paint.Align.LEFT else Paint.Align.RIGHT
                    canvas.drawText(sym, if (leftHalf) r.left + dp(7f) else r.right - dp(7f), r.top + dp(14.5f), small)
                }
            }
        }
    }

    private fun drawWord(canvas: Canvas, w: String, cx: Float, cy: Float, size: Float, color: Int = WHITE) {
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
        icon.color = if (shift == Shift.OFF) WHITE else 0xFFffb259.toInt()
        icon.style = if (shift == Shift.OFF) Paint.Style.STROKE else Paint.Style.FILL_AND_STROKE
        canvas.drawPath(p, icon)
        if (shift == Shift.CAPS) canvas.drawLine(cx - s * 0.7f, cy + s * 1.25f, cx + s * 0.7f, cy + s * 1.25f, icon)
        icon.style = Paint.Style.STROKE
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(10f); val h = dp(7f)
        icon.color = WHITE
        val p = Path().apply {
            moveTo(cx + w, cy - h); lineTo(cx + w, cy + h * 0.4f); lineTo(cx - w, cy + h * 0.4f)
            moveTo(cx - w + dp(5f), cy + h * 0.4f - dp(5f)); lineTo(cx - w, cy + h * 0.4f); lineTo(cx - w + dp(5f), cy + h * 0.4f + dp(5f))
        }
        canvas.drawPath(p, icon)
    }

    // ---- touch: act on press, several fingers at once

    private fun hit(x: Float, y: Float): Cell? {
        if (cells.isEmpty()) return null
        val r = ((y - padTop + rowGap / 2) / (keyH + rowGap)).toInt().coerceIn(0, cells.size - 1)
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
