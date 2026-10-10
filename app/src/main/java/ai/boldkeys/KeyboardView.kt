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

    private companion object { const val WHITE = 0xFFF4F6F8.toInt() }

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
    private val surf = Paint(Paint.ANTI_ALIAS_FLAG)
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

    /**
     * A key is a small solid: a dark side wall, and above it the top surface lit from the top-left (a lighter upper edge, a shaded lower-right,
     * a soft sheen). Near the edge facing the middle of the keyboard the top slopes away in a crescent that catches light on its crest and
     * falls into shadow at the key's edge. Pressing a key drops the top onto the wall.
     */
    private fun drawKey(canvas: Canvas, c: Cell, isDown: Boolean) {
        val r = c.rect
        val wall = dp(3f)
        val drop = if (isDown) wall * 0.8f else 0f
        val t = RectF(r.left, r.top + drop, r.right, r.bottom - wall + drop)
        val rad = dp(5f)
        val special = c.key.kind != Kind.CHAR && c.key.kind != Kind.SPACE
        val leftHalf = r.centerX() < width / 2f
        val hasFacet = c.key.kind != Kind.SPACE
        val tw = t.width(); val th = t.height()

        // soft shadow on the plate, then the side wall
        surf.shader = null; surf.color = 0x99000000.toInt()
        canvas.drawPath(capPath(RectF(r).apply { offset(0f, dp(1.2f)) }, rad, dp(0.6f)), surf)
        surf.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF2d343d.toInt(), 0xFF040608.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(capPath(r, rad, dp(0.6f)), surf)

        // top surface
        val top = capPath(t, rad, dp(1f))
        val hi = if (special) 0xFF2b323b.toInt() else 0xFF343c46.toInt()
        val lo = if (special) 0xFF0c0f13.toInt() else 0xFF0f1318.toInt()
        surf.shader = LinearGradient(0f, t.top, 0f, t.bottom, hi, lo, Shader.TileMode.CLAMP)
        canvas.drawPath(top, surf)
        // light falls from the top-left: a soft sheen there, shade to the lower right
        surf.shader = RadialGradient(t.left + tw * 0.28f, t.top + th * 0.18f, tw * 0.95f, 0x3DFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawPath(top, surf)
        surf.shader = LinearGradient(t.left, t.top, t.right, t.bottom, 0x00000000, 0x66000000, Shader.TileMode.CLAMP)
        canvas.drawPath(top, surf)
        if (isDown) { surf.shader = null; surf.color = 0x33000000; canvas.drawPath(top, surf) }
        surf.shader = null
        // a thin bright edge along the top and the lit corner, a dark one on the far side
        line.strokeWidth = dp(0.9f)
        line.shader = LinearGradient(t.left, t.top, t.right, t.bottom, intArrayOf(0x88FFFFFF.toInt(), 0x22FFFFFF, 0x00000000, 0x88000000.toInt()), floatArrayOf(0f, 0.3f, 0.6f, 1f), Shader.TileMode.CLAMP)
        canvas.drawPath(top, line)
        line.shader = null

        // where the legends may go: the part of the top beside the facet
        var areaL = t.left; var areaR = t.right
        if (hasFacet) {
            fun fx(f: Float) = if (leftHalf) t.left + tw * f else t.right - tw * f
            val tipX = fx(0.64f); val tipY = t.top + th * 0.10f
            val botX = fx(0.97f); val botY = t.top + th * 0.90f
            val facet = Path().apply {
                moveTo(tipX, tipY)
                quadTo(fx(1.04f), t.top + th * 0.24f, botX, botY)
                quadTo(fx(0.78f), t.top + th * 0.55f, tipX, tipY)
                close()
            }
            // the slope: light on the crest (toward the key's middle), shadow at the edge
            surf.shader = LinearGradient(tipX, 0f, botX, 0f, 0x66FFFFFF, 0xAA000000.toInt(), Shader.TileMode.CLAMP)
            canvas.drawPath(facet, surf)
            surf.shader = null
            // the crest: a fine highlight that fades out toward both ends
            val crest = Path().apply { moveTo(tipX, tipY); quadTo(fx(0.78f), t.top + th * 0.55f, botX, botY) }
            // a dark line just inside the crest gives the step its depth, the bright line on it is the lit edge
            line.strokeWidth = dp(1.1f); line.shader = null; line.color = 0x88000000.toInt()
            canvas.save(); canvas.translate(if (leftHalf) -dp(1.2f) else dp(1.2f), 0f); canvas.drawPath(crest, line); canvas.restore()
            line.strokeWidth = dp(1.1f)
            line.shader = LinearGradient(0f, tipY, 0f, botY, intArrayOf(0x22FFFFFF, 0xEEFFFFFF.toInt(), 0x66FFFFFF), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawPath(crest, line)
            line.shader = null
            if (leftHalf) areaR = tipX - dp(1f) else areaL = tipX + dp(1f)
        } else if (!isDown) {
            // the space bar's chrome lip
            line.strokeWidth = dp(1.2f)
            line.shader = LinearGradient(t.left, 0f, t.right, 0f, intArrayOf(0x00FFFFFF, 0xCCFFFFFF.toInt(), 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            canvas.drawPath(Path().apply { moveTo(t.left + dp(10f), t.bottom - dp(5f)); quadTo(t.centerX(), t.bottom - dp(1f), t.right - dp(10f), t.bottom - dp(5f)) }, line)
            line.shader = null
        }

        val acx = (areaL + areaR) / 2f
        val aw = areaR - areaL
        val cy = t.centerY() + dp(1f)
        legend.color = WHITE
        legend.setShadowLayer(dp(0.6f), 0f, dp(0.7f), 0xAA000000.toInt())
        when (c.key.kind) {
            Kind.SHIFT -> { clearShadow(); drawShift(canvas, acx, cy) }
            Kind.ENTER -> { clearShadow(); drawEnter(canvas, acx, cy) }
            Kind.DELETE -> fitWord(canvas, "del", acx, cy, dp(13f), aw - dp(5f))
            Kind.SPACE -> fitWord(canvas, "space", acx, cy - dp(2f), dp(13f), aw)
            Kind.LAYER -> fitWord(canvas, "alt", acx, cy, dp(13.5f), aw - dp(5f), if (altLayer) 0xFFffb259.toInt() else WHITE)
            Kind.CHAR -> {
                val isLetter = c.key.label.length == 1 && c.key.label[0].isLetter()
                if (isLetter && !altLayer) {
                    legend.textSkewX = -0.24f
                    legend.textSize = dp(17.5f)
                    canvas.drawText(c.key.label, acx, cy - (legend.ascent() + legend.descent()) / 2 + dp(3f), legend)
                    legend.textSkewX = 0f
                } else {
                    legend.textSize = dp(19f)
                    canvas.drawText(c.key.label, acx, cy - (legend.ascent() + legend.descent()) / 2, legend)
                }
                clearShadow()
                if (!altLayer && isLetter) secondaryFor(c.row, c.col)?.let { sym ->
                    small.textSize = dp(12f)
                    small.color = WHITE
                    small.textAlign = if (leftHalf) Paint.Align.LEFT else Paint.Align.RIGHT
                    canvas.drawText(sym, if (leftHalf) areaL + dp(5.5f) else areaR - dp(5.5f), t.top + dp(14f), small)
                }
            }
        }
        clearShadow()
    }

    private fun clearShadow() = legend.clearShadowLayer()

    /** Draws a word centred, shrinking it if needed so it never reaches past [maxW]. */
    private fun fitWord(canvas: Canvas, w: String, cx: Float, cy: Float, size: Float, maxW: Float, color: Int = WHITE) {
        legend.color = color
        legend.textSize = size
        while (legend.measureText(w) > maxW && legend.textSize > dp(8f)) legend.textSize -= dp(0.5f)
        canvas.drawText(w, cx, cy - (legend.ascent() + legend.descent()) / 2, legend)
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
