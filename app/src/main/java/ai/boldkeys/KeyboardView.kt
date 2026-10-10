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

    private var th = KeyTheme(true)
    private var showEmoji = true
    private var glowMode = "off"
    private var glowRgb = 0xFFEAF2FF.toInt()
    private var glowSetting = 0.6f
    private var ambient = 1f // 1 in the dark, 0 in bright light (from the phone's light sensor); only used when the backlight is on Auto
    private var glow = 0f

    private fun symPages() = if (showEmoji) Layouts.symbolPages else Layouts.symbolPages.take(Layouts.SYMBOL_ONLY)

    /** Reads the look and behaviour the user chose; called whenever the keyboard is shown. */
    fun applySettings() {
        val c = context
        th = KeyTheme(Prefs.isDark(c))
        showEmoji = Prefs.emojiPages(c)
        glowMode = Prefs.backlightMode(c)
        glowRgb = Prefs.BACKLIGHT_COLORS[Prefs.backlightColor(c)] ?: 0xFFEAF2FF.toInt()
        glowSetting = Prefs.backlightLevel(c)
        updateGlow()
        faces.clear()
        if (symPage >= symPages().size) symPage = -1
        invalidate()
    }

    private fun updateGlow() {
        val g = if (!th.dark) 0f else when (glowMode) { "on" -> glowSetting; "auto" -> glowSetting * ambient; else -> 0f }
        if (g != glow) { glow = g; invalidate() }
    }

    private fun glowArgb(alpha: Float) = (glowRgb and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255f).toInt() shl 24)

    // the phone's light sensor drives the backlight on Auto, as the original's did
    private val sensors by lazy { context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager }
    private val lightListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(e: android.hardware.SensorEvent) {
            val lux = e.values[0]
            val target = 1f - smooth(8f, 160f, lux)
            if (kotlin.math.abs(target - ambient) > 0.04f) { ambient = target; updateGlow() }
        }
        override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensors.getDefaultSensor(android.hardware.Sensor.TYPE_LIGHT)?.let { sensors.registerListener(lightListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL) }
        applySettings()
    }

    override fun onDetachedFromWindow() { sensors.unregisterListener(lightListener); super.onDetachedFromWindow() }

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
    private var altHeld = false // a finger is on Alt right now: keys type their symbols for as long as it stays there (the other thumb types)
    private var altUsedWhileHeld = false
    private var lastShiftTap = 0L
    /** The SYM picker: -1 when closed, otherwise the page showing. Pressing SYM again moves to the next page. */
    private var symPage = -1
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
        canvas.drawColor(th.bg)
        // the silver ridges above, between and below the rows, following the same smile as the keys
        for (r in 0..cells.size) drawRidge(canvas, padTop + r * (keyH + rowGap) - rowGap / 2)
        val down = pressed.values.toSet()
        for (row in cells) for (c in row) drawKey(canvas, c, c in down || latched(c.key))
        if (symPage >= 0) drawSymPanel(canvas)
    }

    /** A modifier that is on stays pressed in, like a held key; nothing else about the keyboard changes. */
    private fun latched(k: Key) = (k.kind == Kind.LAYER && altMode != Shift.OFF) || (k.kind == Kind.SHIFT && shift != Shift.OFF)

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
    private fun keyFace(w: Int, h: Int, mirror: Boolean, facet: Boolean, special: Boolean): Bitmap = faces.getOrPut("$w,$h,$mirror,$facet,$special,${th.dark}") {
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
        val base = if (special) th.baseSpecial else th.base
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
            val spec = max(0f, nx * hx / hl + ny * hy / hl + nz * hz / hl).pow(26f) * th.specular
            val ao = 0.80f + 0.20f * smooth(0f, bevel * 1.4f, dist[i])
            val k = (th.ambient + th.diffuse * diff) * ao
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

        if (glow > 0.01f) {
            // light leaking out from under the key: a few widening, fading outlines, strongest right at the key's edge
            surf.shader = null
            for (i in 4 downTo 1) {
                val grow = dp(1.5f) * i
                surf.color = glowArgb(glow * 0.20f / i)
                canvas.drawRoundRect(RectF(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow), rad + grow, rad + grow, surf)
            }
        }
        surf.shader = null; surf.color = th.cast
        canvas.drawPath(capPath(RectF(r).apply { offset(0f, dp(1.2f)) }, rad, dp(0.6f)), surf)
        surf.shader = LinearGradient(0f, r.top, 0f, r.bottom, th.wallTop, th.wallBottom, Shader.TileMode.CLAMP)
        canvas.drawPath(capPath(r, rad, dp(0.6f)), surf)
        surf.shader = null

        val face = keyFace(faceW.coerceAtLeast(1), faceH.coerceAtLeast(1), false, hasFacet, special)
        surf.colorFilter = if (isDown) pressFilter else null
        canvas.drawBitmap(face, t.left, t.top, surf)
        surf.colorFilter = null

        // Legends sit in the middle of the lit face (the sloped part is shading, so the face is the left ~70%), and are fitted so they never overflow it.
        val flat = c.key.kind != Kind.SPACE
        val acx = if (flat) t.left + tw * 0.44f else t.centerX()
        val maxW = if (flat) tw * 0.70f - dp(3f) else tw - dp(12f)
        val cy = t.centerY() + dp(1f)
        legend.color = th.legend
        legendShadow()
        when (c.key.kind) {
            Kind.SHIFT -> { clearShadow(); drawShiftKey(canvas, acx, cy, c.col == 0, maxW) }
            Kind.ENTER -> { clearShadow(); drawEnter(canvas, acx, cy) }
            Kind.DELETE -> {
                fitWord(canvas, "del", acx, cy - dp(3.5f), dp(12.5f), maxW)
                clearShadow(); drawBackArrow(canvas, acx, cy + dp(8f))
            }
            Kind.SPACE -> fitWord(canvas, "space", acx, cy - dp(2f), dp(13f), maxW)
            Kind.SYM -> fitWord(canvas, "sym", acx, cy, dp(13f), maxW)
            Kind.LAYER -> {
                fitWord(canvas, "alt", acx, cy, dp(13f), maxW)
                if (altMode == Shift.CAPS) { // locked: a thin bar under the word, same white
                    clearShadow(); line.shader = null; line.strokeWidth = dp(1.2f); line.color = th.legend
                    canvas.drawLine(acx - dp(7f), cy + dp(10f), acx + dp(7f), cy + dp(10f), line)
                }
            }
            Kind.CHAR -> {
                val isLetter = c.key.label.length == 1 && c.key.label[0].isLetter()
                if (isLetter) {
                    legend.textSize = dp(17.5f)
                    drawInk(canvas, c.key.label, acx, cy + dp(3f), -0.24f)
                } else { // 0 and $ stand upright
                    legend.textSize = dp(18f)
                    drawInk(canvas, c.key.label, acx, cy + dp(2f))
                }
                clearShadow()
                val sym = secondaryFor(c.row, c.col)
                val rightSide = r.centerX() > width / 2f // from Y, H and B onward the small symbol sits in the right corner, as printed on the phone
                if (sym != null && isLetter) {
                    small.textSize = dp(11.5f); small.color = th.legend; small.textAlign = Paint.Align.LEFT
                    if (glow > 0.01f) small.setShadowLayer(dp(3.5f), 0f, 0f, glowArgb(glow * 0.8f)) else small.clearShadowLayer()
                    small.getTextBounds(sym, 0, sym.length, ink)
                    val gapEdge = dp(8f) // the same distance from either edge, measured to the glyph itself
                    val x = if (rightSide) t.right - gapEdge - ink.right else t.left + gapEdge - ink.left
                    canvas.drawText(sym, x, t.top + dp(14f), small)
                } else if (c.key.label == "$") {
                    drawSpeaker(canvas, t.right - dp(15f), t.top + dp(9f))
                }
            }
        }
        clearShadow()
    }

    private fun clearShadow() = legend.clearShadowLayer()

    /** The letters' shadow: a plain drop shadow, or with the backlight on a soft glow of the chosen colour around them. */
    private fun legendShadow() {
        if (glow > 0.01f) legend.setShadowLayer(dp(5f), 0f, 0f, glowArgb(glow * 0.95f))
        else legend.setShadowLayer(dp(0.6f), 0f, dp(0.7f), th.legendShadow)
    }

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
    private fun fitWord(canvas: Canvas, w: String, cx: Float, cy: Float, size: Float, maxW: Float, color: Int = th.legend) {
        legend.color = color
        legend.textSize = size
        while (legend.measureText(w) > maxW && legend.textSize > dp(8f)) legend.textSize -= dp(0.5f)
        drawInk(canvas, w, cx, cy)
    }

    /** The Bold's shift key: "aA" with a small outlined arrow standing over the "a" (left key) or the "A" (right key). Caps lock adds a bar; the colour never changes. */
    private fun drawShiftKey(canvas: Canvas, cx: Float, cy: Float, left: Boolean, maxW: Float) {
        legend.textSize = dp(13f); legend.color = th.legend
        while (legend.measureText("aA") > maxW && legend.textSize > dp(8f)) legend.textSize -= dp(0.5f)
        val tw = legend.measureText("aA")
        legendShadow()
        drawInk(canvas, "aA", cx, cy + dp(5f))
        clearShadow()
        // the arrow over the outer letter
        val s = dp(4.6f)
        val ax = cx - tw / 2f + legend.measureText("a") / 2f // always over the small a, which is the first letter of "aA"
        val ay = cy - dp(5.5f)
        icon.color = th.legend; icon.style = Paint.Style.STROKE; icon.strokeWidth = dp(1.2f)
        canvas.drawPath(Path().apply {
            moveTo(ax, ay - s); lineTo(ax + s, ay); lineTo(ax + s * 0.45f, ay); lineTo(ax + s * 0.45f, ay + s * 0.85f)
            lineTo(ax - s * 0.45f, ay + s * 0.85f); lineTo(ax - s * 0.45f, ay); lineTo(ax - s, ay); close()
        }, icon)
        if (shift == Shift.CAPS) canvas.drawLine(ax - s * 0.7f, ay + s * 1.3f, ax + s * 0.7f, ay + s * 1.3f, icon)
        icon.strokeWidth = dp(1.8f)
    }

    private fun drawBackArrow(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(6f); val h = dp(2.5f)
        icon.color = th.legend; icon.strokeWidth = dp(1.1f)
        canvas.drawPath(Path().apply { moveTo(cx + w, cy); lineTo(cx - w, cy); moveTo(cx - w + h, cy - h); lineTo(cx - w, cy); lineTo(cx - w + h, cy + h) }, icon)
        icon.strokeWidth = dp(1.8f)
    }

    /** The little speaker printed on the $ key. */
    private fun drawSpeaker(canvas: Canvas, x: Float, y: Float) {
        val s = dp(2.9f)
        icon.color = th.legend; icon.strokeWidth = dp(0.9f)
        canvas.drawPath(Path().apply {
            moveTo(x, y - s * 0.5f); lineTo(x + s * 0.7f, y - s * 0.5f); lineTo(x + s * 1.5f, y - s); lineTo(x + s * 1.5f, y + s); lineTo(x + s * 0.7f, y + s * 0.5f); lineTo(x, y + s * 0.5f); close()
            moveTo(x + s * 2.1f, y - s * 0.6f); quadTo(x + s * 2.7f, y, x + s * 2.1f, y + s * 0.6f)
            moveTo(x + s * 2.6f, y - s * 1.1f); quadTo(x + s * 3.5f, y, x + s * 2.6f, y + s * 1.1f)
        }, icon)
        icon.strokeWidth = dp(1.8f)
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float) {
        val w = dp(7.5f); val h = dp(5.5f)
        icon.color = th.legend; icon.strokeWidth = dp(1.5f)
        val p = Path().apply {
            moveTo(cx + w, cy - h); lineTo(cx + w, cy + h * 0.4f); lineTo(cx - w, cy + h * 0.4f)
            moveTo(cx - w + dp(4f), cy + h * 0.4f - dp(4f)); lineTo(cx - w, cy + h * 0.4f); lineTo(cx - w + dp(4f), cy + h * 0.4f + dp(4f))
        }
        canvas.drawPath(p, icon)
        icon.strokeWidth = dp(1.8f)
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
                if (symPage >= 0) { symTouch(e.getX(i), e.getY(i)); return true }
                hit(e.getX(i), e.getY(i))?.let { c ->
                    pressed[e.getPointerId(i)] = c
                    feedback.key()
                    pressedRow = c.row; pressedCol = c.col
                    if (c.key.kind == Kind.LAYER) { altHeld = true; altUsedWhileHeld = false }
                    fire(c.key)
                    if (c.key.kind == Kind.DELETE) ui.postDelayed(repeatDelete, 380)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val up = pressed.remove(e.getPointerId(e.actionIndex))
                if (up?.key?.kind == Kind.LAYER) {
                    altHeld = false
                    if (altUsedWhileHeld && altMode == Shift.ONCE) altMode = Shift.OFF // held and used: it ends with the finger; a plain tap stays armed for the next key
                    altUsedWhileHeld = false
                }
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
                if (altMode != Shift.OFF && key.label == "$") {
                    // Alt + $ is the speaker on the real phone; with no sound to switch it does nothing, but it still uses up the Alt
                    if (altHeld) altUsedWhileHeld = true else if (altMode == Shift.ONCE) altMode = Shift.OFF
                    invalidate(); return
                } else if (altMode != Shift.OFF && sym != null) {
                    t = sym
                    if (altHeld) altUsedWhileHeld = true else if (altMode == Shift.ONCE) altMode = Shift.OFF
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
            Kind.SYM -> symPage = 0
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

    // ---- the SYM picker: a grid of symbols; SYM cycles the pages

    private class SymLayout(val cells: List<RectF>, val more: RectF, val close: RectF)

    private fun symLayout(): SymLayout {
        val pad = dp(8f); val gapX = dp(6f); val gapY = dp(6f)
        val cols = 5; val rowsN = 4
        val pillH = dp(34f)
        val gridH = height - pad * 2 - pillH - dp(8f)
        val cw = (width - pad * 2 - gapX * (cols - 1)) / cols
        val ch = (gridH - gapY * (rowsN - 1)) / rowsN
        val cells = ArrayList<RectF>()
        for (r in 0 until rowsN) for (c in 0 until cols) {
            val l = pad + c * (cw + gapX); val tp = pad + r * (ch + gapY)
            cells.add(RectF(l, tp, l + cw, tp + ch))
        }
        val y = height - pad - pillH
        val half = (width - pad * 2 - gapX) / 2
        return SymLayout(cells, RectF(pad, y, pad + half, y + pillH), RectF(pad + half + gapX, y, width - pad, y + pillH))
    }

    private fun drawSymPanel(canvas: Canvas) {
        val lay = symLayout()
        fill.shader = null; fill.color = th.panel
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        val page = symPages()[symPage % symPages().size]
        legend.color = th.legend
        for ((i, r) in lay.cells.withIndex()) {
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, th.padTop, th.padBottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, dp(7f), dp(7f), fill)
            fill.shader = null
            legend.textSize = dp(21f)
            drawInk(canvas, page.getOrNull(i) ?: "", r.centerX(), r.centerY())
        }
        for ((r, label) in listOf(lay.more to "more", lay.close to "close")) {
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, th.pillTop, th.pillBottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, dp(17f), dp(17f), fill)
            fill.shader = null
            legend.textSize = dp(13f)
            drawInk(canvas, label, r.centerX(), r.centerY())
        }
    }

    private fun symTouch(x: Float, y: Float) {
        val lay = symLayout()
        val page = symPages()[symPage % symPages().size]
        feedback.key()
        when {
            lay.more.contains(x, y) -> symPage = (symPage + 1) % symPages().size
            lay.close.contains(x, y) -> symPage = -1
            else -> {
                val i = lay.cells.indexOfFirst { it.contains(x, y) }
                if (i >= 0 && i < page.size) listener.onText(page[i]) // the picker stays open: only "close" puts the keys back
            }
        }
        invalidate()
    }

    /** True while a finger rests on a shift key (the trackpad then selects text). */
    fun isShiftHeld() = pressed.values.any { it.key.kind == Kind.SHIFT }

    /** Called after text changes: capitalise the next letter at the start of a sentence. */
    fun setAutoShift(on: Boolean) {
        if (shift == Shift.CAPS) return
        val want = if (on) Shift.ONCE else Shift.OFF
        if (shift != want) { shift = want; invalidate() }
    }
}
