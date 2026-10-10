package ai.boldkeys

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LightingColorFilter
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

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
    /** Alt works like on the real keyboard: tap it and the next key types its small symbol; double-tap to keep it on. */
    private var altMode = Shift.OFF
    private var lastAltTap = 0L
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { faces.clear(); rebuild() }

    private fun rows() = Layouts.letterRows

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

    private val faces = HashMap<String, Bitmap>()
    private val pressFilter = LightingColorFilter(0xFFB4B4B4.toInt(), 0)

    private fun smooth(e0: Float, e1: Float, x: Float): Float { val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /**
     * The top of a key, worked out as a real surface and lit from the top-left, pixel by pixel: a rounded edge, a gently domed top, and a smooth
     * slope toward the middle of the keyboard (the Bold's sculpted facet). The light and shade on that shape is what makes it look solid; nothing
     * is drawn on top of it. Rendered once per key size and kept.
     */
    private fun keyFace(w: Int, h: Int, mirror: Boolean, facet: Boolean, special: Boolean): Bitmap = faces.getOrPut("$w,$h,$mirror,$facet,$special") {
        val rad = dp(5f); val bevel = dp(3.6f); val hk = dp(4.6f); val hd = dp(2.6f); val af = dp(7.5f)
        val z = FloatArray(w * h); val alpha = FloatArray(w * h); val dist = FloatArray(w * h)
        val hw = w / 2f; val hh = h / 2f
        for (y in 0 until h) for (x in 0 until w) {
            val px = x + 0.5f - hw; val py = y + 0.5f - hh
            val qx = abs(px) - (hw - rad); val qy = abs(py) - (hh - rad)
            val outside = sqrt(max(qx, 0f) * max(qx, 0f) + max(qy, 0f) * max(qy, 0f))
            val d = -(outside + min(max(qx, qy), 0f) - rad)
            val i = y * w + x
            dist[i] = d
            alpha[i] = (d + 0.5f).coerceIn(0f, 1f)
            val u = (x + 0.5f) / w; val v = (y + 0.5f) / h
            val edge = sin((Math.PI / 2).toFloat() * (d / bevel).coerceIn(0f, 1f))
            val dome = hd * (1f - ((2f * u - 1f).pow(2) + (2f * v - 1f).pow(2)) / 2f)
            var zz = hk * edge + dome
            if (facet) {
                val uu = if (mirror) 1f - u else u
                val c = 0.86f - 0.16f * v // the slope starts further out at the top and reaches inward lower down, like a leaf
                val f = smooth(c, c + 0.20f, uu) * smooth(0f, 0.34f, v) // it runs to the bottom edge, where the rounded edge takes over
                zz -= af * f
            }
            z[i] = zz
        }
        val base = if (special) floatArrayOf(31f, 38f, 47f) else floatArrayOf(36f, 44f, 54f)
        val lx = -0.12f; val ly = -0.70f; val lz = 0.72f // light from nearly overhead, a little from the left, so both halves of the board match
        val ll = sqrt(lx * lx + ly * ly + lz * lz)
        val hx = lx / ll; val hy = ly / ll; val hz = lz / ll + 1f
        val hl = sqrt(hx * hx + hy * hy + hz * hz)
        val out = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (alpha[i] <= 0f) continue
            val xm = max(x - 1, 0); val xp = min(x + 1, w - 1); val ym = max(y - 1, 0); val yp = min(y + 1, h - 1)
            val dzx = (z[y * w + xp] - z[y * w + xm]) / (xp - xm).coerceAtLeast(1)
            val dzy = (z[yp * w + x] - z[ym * w + x]) / (yp - ym).coerceAtLeast(1)
            val nl = sqrt(dzx * dzx + dzy * dzy + 1f)
            val nx = -dzx / nl; val ny = -dzy / nl; val nz = 1f / nl
            val diff = max(0f, nx * lx / ll + ny * ly / ll + nz * lz / ll)
            val spec = max(0f, nx * hx / hl + ny * hy / hl + nz * hz / hl).pow(26f) * 0.5f
            val ao = 0.80f + 0.20f * smooth(0f, bevel * 1.4f, dist[i])
            val k = (0.24f + 1.30f * diff) * ao
            val r = (base[0] * k + spec * 255f).coerceIn(0f, 255f).toInt()
            val g = (base[1] * k + spec * 255f).coerceIn(0f, 255f).toInt()
            val b = (base[2] * k + spec * 255f).coerceIn(0f, 255f).toInt()
            out[i] = ((alpha[i] * 255f).toInt() shl 24) or (r shl 16) or (g shl 8) or b
        }
        Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
    }

    /** A key: a soft shadow, the dark wall it stands on, and its lit top. Pressing drops the top onto the wall. */
    private fun drawKey(canvas: Canvas, c: Cell, isDown: Boolean) {
        val r = c.rect
        val wall = dp(3f)
        val drop = if (isDown) wall * 0.8f else 0f
        val faceW = Math.round(r.width()); val faceH = Math.round(r.height() - wall)
        val faceX = Math.round(r.left); val faceY = Math.round(r.top + drop)
        val t = RectF(faceX.toFloat(), faceY.toFloat(), (faceX + faceW).toFloat(), (faceY + faceH).toFloat())
        val rad = dp(5f)
        val special = c.key.kind != Kind.CHAR && c.key.kind != Kind.SPACE
        val hasFacet = c.key.kind != Kind.SPACE
        val tw = t.width()

        surf.shader = null; surf.color = 0x99000000.toInt()
        canvas.drawPath(capPath(RectF(r).apply { offset(0f, dp(1.2f)) }, rad, dp(0.6f)), surf)
        surf.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF2d343d.toInt(), 0xFF040608.toInt(), Shader.TileMode.CLAMP)
        canvas.drawPath(capPath(r, rad, dp(0.6f)), surf)
        surf.shader = null

        val face = keyFace(faceW.coerceAtLeast(1), faceH.coerceAtLeast(1), false, hasFacet, special)
        surf.colorFilter = if (isDown) pressFilter else null
        canvas.drawBitmap(face, t.left, t.top, surf)
        surf.colorFilter = null

        // legends are centred on the key itself and fitted to its width (the slope is only shading, so text may sit over it)
        val areaL = t.left; val areaR = t.right
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
            Kind.LAYER -> {
                fitWord(canvas, "alt", acx, cy, dp(13.5f), aw - dp(5f), if (altMode != Shift.OFF) 0xFFffb259.toInt() else WHITE)
                if (altMode == Shift.CAPS) { // locked: a small bar under the word
                    clearShadow(); line.shader = null; line.strokeWidth = dp(1.5f); line.color = 0xFFffb259.toInt()
                    canvas.drawLine(acx - dp(7f), cy + dp(10f), acx + dp(7f), cy + dp(10f), line)
                }
            }
            Kind.CHAR -> {
                val isLetter = c.key.label.length == 1 && c.key.label[0].isLetter()
                val sym = if (isLetter) secondaryFor(c.row, c.col) else null
                val alt = altMode != Shift.OFF && sym != null
                if (alt) {
                    // Alt is on: the small symbol becomes the big one, and the letter steps back into the corner
                    legend.textSize = dp(19f)
                    legend.color = 0xFFffb259.toInt()
                    while (legend.measureText(sym!!) > aw - dp(10f) && legend.textSize > dp(9f)) legend.textSize -= dp(0.5f)
                    drawInk(canvas, sym, acx, cy)
                    clearShadow()
                    small.textSize = dp(11f); small.color = 0xFF8f959c.toInt(); small.textAlign = Paint.Align.LEFT
                    canvas.drawText(c.key.label, areaL + dp(6f), t.top + dp(14f), small)
                } else if (isLetter) {
                    legend.textSize = dp(17.5f)
                    drawInk(canvas, c.key.label, acx, cy + dp(3f), -0.24f)
                    clearShadow()
                    if (sym != null) {
                        small.textSize = dp(11.5f); small.color = WHITE; small.textAlign = Paint.Align.LEFT
                        canvas.drawText(sym, areaL + dp(6f), t.top + dp(14f), small)
                    }
                } else {
                    legend.textSize = dp(17f)
                    while (legend.measureText(c.key.label) > aw - dp(10f) && legend.textSize > dp(9f)) legend.textSize -= dp(0.5f)
                    drawInk(canvas, c.key.label, acx, cy)
                }
            }
        }
        clearShadow()
    }

    private fun clearShadow() = legend.clearShadowLayer()

    private val ink = android.graphics.Rect()

    /** Draws [text] so the middle of its actual shape sits at [cx] sideways, on the line through [cy]; [lean] tips it like italics around that middle, so it never shifts sideways. */
    private fun drawInk(canvas: Canvas, text: String, cx: Float, cy: Float, lean: Float = 0f) {
        legend.textAlign = Paint.Align.LEFT
        legend.getTextBounds(text, 0, text.length, ink)
        canvas.save()
        canvas.translate(cx, cy)
        if (lean != 0f) canvas.skew(lean, 0f)
        canvas.drawText(text, -(ink.left + ink.right) / 2f, -(legend.ascent() + legend.descent()) / 2f, legend) // centred sideways by its shape, but on the normal baseline so punctuation keeps its height
        canvas.restore()
        legend.textAlign = Paint.Align.CENTER
    }

    /** Draws a word centred, shrinking it if needed so it never reaches past [maxW]. */
    private fun fitWord(canvas: Canvas, w: String, cx: Float, cy: Float, size: Float, maxW: Float, color: Int = WHITE) {
        legend.color = color
        legend.textSize = size
        while (legend.measureText(w) > maxW && legend.textSize > dp(8f)) legend.textSize -= dp(0.5f)
        drawInk(canvas, w, cx, cy)
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
                    pressedRow = c.row; pressedCol = c.col
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

    private var pressedRow = 0
    private var pressedCol = 0

    private fun fire(key: Key) {
        when (key.kind) {
            Kind.CHAR, Kind.SPACE -> {
                var t = key.text
                val isLetter = key.kind == Kind.CHAR && t.length == 1 && t[0].isLetter()
                val sym = if (isLetter) secondaryFor(pressedRow, pressedCol) else null
                if (altMode != Shift.OFF && sym != null) {
                    t = sym
                    if (altMode == Shift.ONCE) altMode = Shift.OFF
                } else {
                    if (isLetter && shift != Shift.OFF) t = t.uppercase()
                    if (isLetter && shift == Shift.ONCE) shift = Shift.OFF
                }
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
            Kind.LAYER -> {
                val now = SystemClock.uptimeMillis()
                altMode = when {
                    now - lastAltTap < 400 && altMode == Shift.ONCE -> Shift.CAPS
                    altMode == Shift.OFF -> Shift.ONCE
                    else -> Shift.OFF
                }
                lastAltTap = now
            }
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
