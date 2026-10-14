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
        /** A letter was tapped while the Speed key is held; true if it had a shortcut and ran it. */
        fun onShortcut(letter: Char): Boolean = false
        /** A letter was held and an accented form chosen: it replaces the plain letter typed when the key went down. */
        fun onAccent(accent: String) {}
    }

    enum class Shift { OFF, ONCE, CAPS }

    private class Cell(val key: Key, val row: Int, val col: Int, val rect: RectF)

    private var th = KeyTheme(true)
    private var emojiKey = true // the right-hand key opens the emoji picker (a tap) or runs the Speed key (held); off, a tap is a Shift
    private var glowMode = "off"
    private var glowRgb = 0xFFEAF2FF.toInt()
    private var glowSetting = 0.6f
    private var ambient = 1f // 1 in the dark, 0 in bright light (from the phone's light sensor); only used when the backlight is on Auto
    private var glow = 0f

    /** Reads the look and behaviour the user chose; called whenever the keyboard is shown. */
    fun applySettings() {
        val c = context
        th = KeyTheme(Prefs.isDark(c))
        emojiKey = Prefs.emojiPages(c)
        glowMode = Prefs.backlightMode(c)
        glowRgb = Prefs.BACKLIGHT_COLORS[Prefs.backlightColor(c)] ?: 0xFFEAF2FF.toInt()
        glowSetting = Prefs.backlightLevel(c)
        val layout = Prefs.layout(c)
        if (layout != layoutId) { layoutId = layout; rebuild() }
        updateGlow()
        faces.clear()
        if (picker == Picker.EMOJI && !emojiKey) closePicker()
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
    /** The right-hand key: hold it and tap a letter to run that letter's Speed shortcut; a plain tap opens the emoji picker (or, with the emoji key off, works as Shift). */
    private var speedHeld = false
    private var speedUsed = false
    private var speedDownAt = 0L
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

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { faces.clear(); buildBody(); rebuild() }

    /** The keyboard's body: it ends in the same smile as the rows, with rounded corners, so it meets the black navigation bar as one curved shape. */
    private val body = Path()
    private fun ridgeY(y0: Float, x: Float): Float { val t = x / width; return y0 + 4f * smile * t * (1f - t) }

    private fun buildBody() {
        body.reset()
        if (width == 0) return
        val w = width.toFloat()
        val y0 = padTop + 4 * (keyH + rowGap) - rowGap / 2 + dp(2.5f) // just under the last silver ridge
        val r = dp(20f)
        body.moveTo(0f, 0f); body.lineTo(w, 0f)
        body.lineTo(w, y0 - r)
        body.quadTo(w, ridgeY(y0, w - r * 0.35f), w - r, ridgeY(y0, w - r))
        var x = w - r
        while (x > r) { x -= w / 24f; body.lineTo(x.coerceAtLeast(r), ridgeY(y0, x.coerceAtLeast(r))) }
        body.quadTo(0f, ridgeY(y0, r * 0.35f), 0f, y0 - r)
        body.close()
    }

    private var layoutId = "qwerty"
    private fun rows() = Layouts.rows(layoutId)

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
        canvas.drawColor(0xFF000000.toInt()) // below the curve: the same black as the navigation bar
        fill.shader = null; fill.color = th.bg
        canvas.drawPath(body, fill)
        if (picker != Picker.NONE) { drawPicker(canvas); return } // the picker covers the keys: they are not drawn at all, so scrolling it costs only the picker
        // the silver ridges above, between and below the rows, following the same smile as the keys
        canvas.save(); canvas.clipPath(body)
        for (r in 0..cells.size) if (r < cells.size || th.dark) drawRidge(canvas, padTop + r * (keyH + rowGap) - rowGap / 2)
        canvas.restore()
        val down = pressed.values.toSet()
        for (row in cells) for (c in row) drawKey(canvas, c, c in down || latched(c.key))
        if (picker != Picker.NONE) drawPicker(canvas)
        drawAccents(canvas)
    }

    /** A modifier that is on stays pressed in, like a held key; nothing else about the keyboard changes. */
    private fun latched(k: Key) = (k.kind == Kind.LAYER && altMode != Shift.OFF) || ((k.kind == Kind.SHIFT || (k.kind == Kind.SPEED && !emojiKey)) && shift != Shift.OFF)

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
            Kind.SPEED -> { clearShadow(); if (emojiKey) drawDotGrid(canvas, acx, cy) else drawShiftKey(canvas, acx, cy, false, maxW) }
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

    /** The Speed key as printed on the Key2: a grid of nine dots. */
    private fun drawDotGrid(canvas: Canvas, cx: Float, cy: Float) {
        val step = dp(5.6f); val r = dp(1.55f)
        icon.color = th.legend; icon.style = Paint.Style.FILL
        for (i in -1..1) for (j in -1..1) canvas.drawCircle(cx + i * step, cy + j * step, r, icon)
        icon.style = Paint.Style.STROKE
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
        if (picker != Picker.NONE) return pickerTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                hit(e.getX(i), e.getY(i))?.let { c ->
                    val before = modifierState()
                    pressed[e.getPointerId(i)] = c
                    feedback.key()
                    pressedRow = c.row; pressedCol = c.col
                    val wasUpper = shift != Shift.OFF
                    val plain = altMode == Shift.OFF && !altHeld && !speedHeld
                    if (c.key.kind == Kind.LAYER) { altHeld = true; altUsedWhileHeld = false }
                    if (c.key.kind == Kind.SPEED) { speedHeld = true; speedUsed = false; speedDownAt = SystemClock.uptimeMillis() }
                    else if (speedHeld && c.key.kind == Kind.CHAR && c.key.label.length == 1 && c.key.label[0].isLetter()) {
                        speedUsed = true
                        listener.onShortcut(c.key.label[0])
                    } else fire(c.key)
                    if (c.key.kind == Kind.DELETE) ui.postDelayed(repeatDelete, 380)
                    // a letter with accented forms: hold it and they appear (the plain letter is already typed)
                    val base = c.key.label.takeIf { c.key.kind == Kind.CHAR && it.length == 1 && it[0].isLetter() }?.get(0)?.lowercaseChar()
                    if (plain && base != null && Layouts.accents.containsKey(base)) {
                        stopAccents()
                        accentFor = c; accentPointer = e.getPointerId(i); accentUpper = wasUpper
                        ui.postDelayed(showAccents, 420)
                    }
                    if (modifierState() != before) invalidate() else invalidateKey(c)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val pi = e.findPointerIndex(accentPointer)
                if (pi >= 0 && accentRects.isNotEmpty()) {
                    val idx = accentAt(e.getX(pi), e.getY(pi))
                    if (idx != accentIndex) { accentIndex = idx; invalidate() }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val before = modifierState()
                val hadAccents = accentRects.isNotEmpty()
                if (e.getPointerId(e.actionIndex) == accentPointer) {
                    val chosen = accentChoices.getOrNull(accentIndex)
                    val lifted = e.actionMasked != MotionEvent.ACTION_CANCEL
                    stopAccents()
                    if (lifted && chosen != null) { feedback.key(); listener.onAccent(chosen) }
                    invalidate()
                }
                val up = pressed.remove(e.getPointerId(e.actionIndex))
                if (up?.key?.kind == Kind.LAYER) {
                    altHeld = false
                    if (altUsedWhileHeld && altMode == Shift.ONCE) altMode = Shift.OFF // held and used: it ends with the finger; a plain tap stays armed for the next key
                    altUsedWhileHeld = false
                }
                if (up?.key?.kind == Kind.SPEED) {
                    speedHeld = false
                    // let go without using it: a quick tap opens the emoji picker (or is a plain Shift when the emoji key is off); a long hold that did nothing is left alone
                    if (!speedUsed) {
                        if (!emojiKey) fire(Key("shift", "", 1.5f, Kind.SHIFT))
                        else if (SystemClock.uptimeMillis() - speedDownAt < 450) { feedback.key(); openPicker(Picker.EMOJI) }
                    }
                }
                if (pressed.values.none { it.key.kind == Kind.DELETE }) ui.removeCallbacks(repeatDelete)
                if (up == null || hadAccents || modifierState() != before || picker != Picker.NONE) invalidate() else invalidateKey(up)
            }
        }
        return true
    }

    private var pressedRow = 0
    private var pressedCol = 0

    /**
     * A key press only changes that key, so only its square is redrawn (with room for its shadow and glow): the graphics chip then repaints one key
     * instead of the whole keyboard on every touch. Anything that changes more than a key (shift, alt, a picker, the accent row) redraws everything.
     */
    private fun invalidateKey(c: Cell) {
        val pad = dp(14f).toInt()
        invalidate((c.rect.left - pad).toInt(), (c.rect.top - pad).toInt(), (c.rect.right + pad).toInt(), (c.rect.bottom + pad).toInt())
    }
    private fun modifierState() = shift.ordinal * 100 + altMode.ordinal * 10 + (if (altHeld) 5 else 0) + (if (speedHeld) 1 else 0)

    // ---- press and hold on a letter: a row of accented forms to slide to; the plain letter has already been typed, and the choice replaces it
    private var accentFor: Cell? = null
    private var accentPointer = -1
    private var accentChoices: List<String> = emptyList()
    private var accentIndex = -1
    private var accentUpper = false
    private val accentRects = ArrayList<RectF>()
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val showAccents = Runnable {
        val cell = accentFor ?: return@Runnable
        val base = cell.key.label.firstOrNull()?.lowercaseChar() ?: return@Runnable
        val list = Layouts.accents[base] ?: return@Runnable
        accentChoices = list.map { if (accentUpper) it.toString().uppercase() else it.toString() }
        accentIndex = -1
        val w = dp(40f); val h = dp(46f); val pad = dp(4f)
        val total = accentChoices.size * w + pad * 2
        var left = (cell.rect.centerX() - total / 2f).coerceIn(dp(2f), width - total - dp(2f))
        // above the key, or, on the top row where there is no room, over the row itself (the thumb covers the key anyway)
        val top = (cell.rect.top - h - dp(10f)).coerceAtLeast(dp(6f))
        accentRects.clear()
        accentChoices.forEachIndexed { i, _ -> accentRects.add(RectF(left + pad + i * w, top + pad, left + pad + (i + 1) * w, top + h - pad + pad)) }
        feedback.key()
        invalidate()
    }

    private fun stopAccents() {
        ui.removeCallbacks(showAccents)
        accentFor = null; accentPointer = -1; accentChoices = emptyList(); accentIndex = -1; accentRects.clear()
    }

    private fun accentAt(x: Float, y: Float): Int {
        if (accentRects.isEmpty()) return -1
        val top = accentRects.first().top - dp(30f); val bottom = accentRects.first().bottom + dp(70f)
        if (y < top || y > bottom) return -1
        // anywhere along the row picks the nearest accent, so a thumb does not have to stay exactly above one
        return accentRects.indices.minByOrNull { Math.abs(accentRects[it].centerX() - x) } ?: -1
    }

    private fun drawAccents(canvas: Canvas) {
        if (accentRects.isEmpty()) return
        val l = accentRects.first().left - dp(4f); val t = accentRects.first().top - dp(4f)
        val r = accentRects.last().right + dp(4f); val b = accentRects.first().bottom + dp(4f)
        accentPaint.shader = null
        accentPaint.color = 0xAA000000.toInt(); canvas.drawRoundRect(l, t + dp(3f), r, b + dp(3f), dp(10f), dp(10f), accentPaint)
        accentPaint.shader = LinearGradient(0f, t, 0f, b, th.padTop, th.padBottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l, t, r, b, dp(10f), dp(10f), accentPaint)
        accentPaint.shader = null
        accentRects.forEachIndexed { i, rc ->
            if (i == accentIndex) { accentPaint.color = 0xFF2f8fe8.toInt(); canvas.drawRoundRect(rc, dp(7f), dp(7f), accentPaint) }
            legend.color = if (i == accentIndex) 0xFFFFFFFF.toInt() else th.legend
            legend.textSize = dp(24f)
            canvas.drawText(accentChoices[i], rc.centerX(), rc.centerY() + dp(8f), legend)
        }
    }

    private fun fire(key: Key) {
        val shift0 = shift; val alt0 = altMode
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
            Kind.SPEED -> {}
            Kind.SYM -> openPicker(Picker.SYM)
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
        val onlyThisKey = key.kind == Kind.CHAR || key.kind == Kind.SPACE || key.kind == Kind.DELETE || key.kind == Kind.ENTER
        if (onlyThisKey && shift == shift0 && altMode == alt0 && picker == Picker.NONE) cells.getOrNull(pressedRow)?.getOrNull(pressedCol)?.let { invalidateKey(it); return }
        invalidate()
    }

    // ---- the pickers: symbols (the sym key) and emoji (the right-hand key). A grid that scrolls: drag it, or use the trackpad; "more" jumps to the next page or group.

    private enum class Picker { NONE, SYM, EMOJI }
    private var picker = Picker.NONE
    private var pickItems = ArrayList<String>()
    private var pickBases = ArrayList<Emoji.Item?>() // emoji: what is behind each cell, so a hold can offer skin tones
    private var pickSections: List<Pair<String, Int>> = emptyList() // a name, and the index of its first cell
    private var pickCols = 5
    private var pickScroll = 0f
    private var tones: Pair<Map<String, Int>, Int> = emptyMap<String, Int>() to 0

    fun pickerOpen() = picker != Picker.NONE

    private fun openPicker(mode: Picker) {
        picker = mode; pickScroll = 0f; stopTones(); stopMotion()
        pickItems = ArrayList(); pickBases = ArrayList()
        val sections = ArrayList<Pair<String, Int>>()
        if (mode == Picker.SYM) {
            pickCols = 5
            Layouts.symbolPages.forEachIndexed { i, page ->
                sections.add("${i + 1}/${Layouts.symbolPages.size}" to pickItems.size)
                for (s in page) { pickItems.add(s); pickBases.add(null) }
            }
        } else {
            pickCols = 7
            tones = Emoji.toneState(context)
            for (g in Emoji.groups(context)) {
                sections.add(g.name to pickItems.size)
                for (it in g.items) { pickItems.add(Emoji.shown(it, Emoji.tone(it, tones.first, tones.second))); pickBases.add(it) }
                while (pickItems.size % pickCols != 0) { pickItems.add(""); pickBases.add(null) } // each group starts on a fresh row
            }
        }
        pickSections = sections
        invalidate()
    }

    private fun closePicker() { picker = Picker.NONE; stopTones(); stopMotion(); invalidate() }

    private class PickGeo(val grid: RectF, val cw: Float, val ch: Float, val gx: Float, val gy: Float, val more: RectF, val label: RectF, val close: RectF) {
        val stride get() = ch + gy
    }

    private fun pickGeo(): PickGeo {
        val pad = dp(8f); val gapX = dp(6f); val gapY = dp(6f)
        val rowsN = 4
        val pillH = dp(34f)
        val gridH = height - pad * 2 - pillH - dp(8f)
        val cw = (width - pad * 2 - gapX * (pickCols - 1)) / pickCols
        val ch = (gridH - gapY * (rowsN - 1)) / rowsN
        val y = height - pad - pillH
        val pill = (width - pad * 2 - gapX * 2) * 0.3f
        val more = RectF(pad, y, pad + pill, y + pillH)
        val close = RectF(width - pad - pill, y, width - pad, y + pillH)
        return PickGeo(RectF(pad, pad, width - pad, pad + gridH), cw, ch, gapX, gapY, more, RectF(more.right + gapX, y, close.left - gapX, y + pillH), close)
    }

    private fun maxScroll(g: PickGeo): Float {
        val rows = (pickItems.size + pickCols - 1) / pickCols
        return max(0f, rows * g.stride - g.gy - g.grid.height())
    }

    // ---- how the grid moves: it glides to where the trackpad sends it, a finger's flick carries on after it lets go, and the scroll indicator shows only while it moves

    private var scrollTarget = 0f
    private var gliding = false
    private var lastGlideAt = 0L
    private var lastScrollAt = 0L
    private val scroller = android.widget.OverScroller(context, android.view.animation.DecelerateInterpolator(1.4f))
    private var flinging = false
    private var tracker: android.view.VelocityTracker? = null

    /** Moves the grid a fraction of the way to its target each frame, by the time that really passed, so it looks the same at 60 and 120 Hz. */
    private val glideStep = object : Runnable {
        override fun run() {
            if (picker == Picker.NONE) { gliding = false; return }
            val g = pickGeo()
            val now = SystemClock.uptimeMillis(); val dt = (now - lastGlideAt).coerceIn(1L, 50L); lastGlideAt = now
            val diff = scrollTarget - pickScroll
            if (abs(diff) < 0.6f) { pickScroll = scrollTarget; gliding = false; invalidate(); return }
            pickScroll = (pickScroll + diff * (1f - 0.72f.pow(dt / 8.33f))).coerceIn(0f, maxScroll(g))
            lastScrollAt = now
            invalidate(); postOnAnimation(this)
        }
    }

    private val flingStep = object : Runnable {
        override fun run() {
            if (picker == Picker.NONE || !flinging) return
            if (scroller.computeScrollOffset()) {
                pickScroll = scroller.currY.toFloat().coerceIn(0f, maxScroll(pickGeo())); scrollTarget = pickScroll; lastScrollAt = SystemClock.uptimeMillis()
                invalidate(); postOnAnimation(this)
            } else flinging = false
        }
    }

    private fun stopMotion() { gliding = false; flinging = false; scroller.forceFinished(true); scrollTarget = pickScroll }

    private fun glideTo(target: Float) {
        flinging = false; scroller.forceFinished(true)
        scrollTarget = target.coerceIn(0f, maxScroll(pickGeo()))
        lastScrollAt = SystemClock.uptimeMillis()
        if (!gliding) { gliding = true; lastGlideAt = lastScrollAt; postOnAnimation(glideStep) }
    }

    /** The trackpad scrolls the picker: up and down move the grid, a longer swipe sideways jumps to the next page or group. */
    private var padDx = 0
    fun pickerScroll(dx: Int, dy: Int) {
        if (picker == Picker.NONE) return
        if (dy != 0) glideTo((if (gliding) scrollTarget else pickScroll) + dy * dp(23f))
        padDx += dx
        if (abs(padDx) >= 4) { jump(if (padDx > 0) 1 else -1); padDx = 0 }
    }

    /** Scrolls to the start of the next (or previous) page or group, wrapping round. */
    private fun jump(dir: Int) {
        if (pickSections.isEmpty()) return
        val g = pickGeo()
        val row = (pickScroll / g.stride + 0.5f).toInt()
        val here = pickSections.indexOfLast { it.second / pickCols <= row }.coerceAtLeast(0)
        val to = (here + dir + pickSections.size) % pickSections.size
        glideTo(pickSections[to].second / pickCols * g.stride)
    }

    private fun sectionName(g: PickGeo): String {
        val row = (pickScroll / g.stride + 0.5f).toInt()
        return pickSections.lastOrNull { it.second / pickCols <= row }?.first ?: ""
    }

    private val emojiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    /** Each symbol or emoji painted once into a small picture, and each tile's background once per size: scrolling then only moves pictures, and no text is laid out or shaped on any frame. */
    private val glyphs = android.util.LruCache<String, Bitmap>(300)

    private fun glyph(text: String, emoji: Boolean): Bitmap = glyphs.get("$text|$emoji|${th.dark}") ?: run {
        val box = dp(36f).toInt()
        val bmp = Bitmap.createBitmap(box, box, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (emoji) {
            emojiPaint.textSize = dp(24f)
            c.drawText(text, box / 2f, box / 2f - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
        } else {
            legend.color = th.legend; legend.textSize = dp(21f)
            drawInk(c, text, box / 2f, box / 2f)
        }
        bmp.also { glyphs.put("$text|$emoji|${th.dark}", it) }
    }

    private fun tileBackground(w: Int, h: Int): Bitmap = glyphs.get("tile|$w|$h|${th.dark}") ?: run {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, 0f, 0f, h.toFloat(), th.padTop, th.padBottom, Shader.TileMode.CLAMP) }
        Canvas(bmp).drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), dp(7f), dp(7f), p)
        bmp.also { glyphs.put("tile|$w|$h|${th.dark}", it) }
    }

    private fun drawPicker(canvas: Canvas) {
        val g = pickGeo()
        fill.shader = null; fill.color = th.bg // solid underneath, so the keys do not show through the panel
        canvas.drawPath(body, fill)
        fill.color = th.panel
        canvas.drawPath(body, fill)
        pickScroll = pickScroll.coerceIn(0f, maxScroll(g))
        val emoji = picker == Picker.EMOJI
        val tile = tileBackground(g.cw.toInt().coerceAtLeast(1), g.ch.toInt().coerceAtLeast(1))
        canvas.save()
        canvas.clipRect(g.grid)
        val first = (pickScroll / g.stride).toInt().coerceAtLeast(0)
        val last = ((pickScroll + g.grid.height()) / g.stride).toInt()
        for (row in first..last) for (c in 0 until pickCols) {
            val i = row * pickCols + c
            if (i >= pickItems.size) break
            val s = pickItems[i]
            if (s.isEmpty()) continue
            val l = g.grid.left + c * (g.cw + g.gx); val t = g.grid.top + row * g.stride - pickScroll
            canvas.drawBitmap(tile, l, t, blit)
            val pic = glyph(s, emoji)
            canvas.drawBitmap(pic, l + (g.cw - pic.width) / 2f, t + (g.ch - pic.height) / 2f, blit)
            if (emoji && pickBases[i]?.tones?.isNotEmpty() == true) { // a small corner mark: hold this one for skin tones
                fill.shader = null; fill.color = th.barText2
                canvas.drawCircle(l + g.cw - dp(5f), t + g.ch - dp(5f), dp(1.6f), fill)
            }
        }
        canvas.restore()
        // where you are in the list
        val rows = (pickItems.size + pickCols - 1) / pickCols
        val ms = maxScroll(g)
        val since = SystemClock.uptimeMillis() - lastScrollAt
        val fade = when { since < 700 -> 1f; since < 1000 -> 1f - (since - 700) / 300f; else -> 0f } // visible while it moves, then gone
        if (ms > 0f && fade > 0f) {
            val trackH = g.grid.height(); val thumbH = max(dp(28f), trackH * trackH / (rows * g.stride))
            val ty = g.grid.top + (trackH - thumbH) * (pickScroll / ms)
            fill.shader = null; fill.color = (th.barText2 and 0x00FFFFFF) or ((0xAA * fade).toInt() shl 24)
            canvas.drawRoundRect(g.grid.right - dp(4f), ty, g.grid.right - dp(0.5f), ty + thumbH, dp(2f), dp(2f), fill)
            if (since >= 700) postInvalidateOnAnimation()
        }
        for ((r, label) in listOf(g.more to "more", g.close to "close")) {
            fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, th.pillTop, th.pillBottom, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(r, dp(17f), dp(17f), fill)
            fill.shader = null
            legend.textSize = dp(13f)
            drawInk(canvas, label, r.centerX(), r.centerY())
        }
        legend.color = th.barText2; legend.textSize = dp(12f)
        drawInk(canvas, sectionName(g), g.label.centerX(), g.label.centerY())
        drawTones(canvas)
    }

    private fun cellAt(g: PickGeo, x: Float, y: Float): Int {
        if (!g.grid.contains(x, y)) return -1
        val c = ((x - g.grid.left) / (g.cw + g.gx)).toInt().coerceIn(0, pickCols - 1)
        val ry = y - g.grid.top + pickScroll
        val r = (ry / g.stride).toInt()
        if (ry - r * g.stride > g.ch) return -1 // in the gap between rows
        val i = r * pickCols + c
        return if (i in pickItems.indices && pickItems[i].isNotEmpty()) i else -1
    }

    private var pDown = false
    private var pDrag = false
    private var pY = 0f
    private var pStartScroll = 0f
    private var pCell = -1

    private fun pickerTouch(e: MotionEvent): Boolean {
        val g = pickGeo()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMotion() // a touch catches a grid that is still gliding or flying
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                pDown = true; pDrag = false; pY = e.y; pStartScroll = pickScroll
                pCell = cellAt(g, e.x, e.y)
                if (pBases(pCell)?.tones?.isNotEmpty() == true) ui.postDelayed(showTones, 420)
            }
            MotionEvent.ACTION_MOVE -> if (pDown) {
                if (toneRects.isNotEmpty()) {
                    val idx = toneRects.indices.minByOrNull { abs(toneRects[it].centerX() - e.x) } ?: -1
                    if (idx != toneIndex) { toneIndex = idx; invalidate() }
                } else {
                    tracker?.addMovement(e)
                    if (!pDrag && abs(e.y - pY) > dp(8f)) { pDrag = true; ui.removeCallbacks(showTones) }
                    if (pDrag) { pickScroll = (pStartScroll - (e.y - pY)).coerceIn(0f, maxScroll(g)); scrollTarget = pickScroll; lastScrollAt = SystemClock.uptimeMillis(); invalidate() }
                }
            }
            MotionEvent.ACTION_UP -> {
                ui.removeCallbacks(showTones)
                if (pDown) {
                    if (toneRects.isNotEmpty()) chooseTone()
                    else if (!pDrag) pickerTap(g, e.x, e.y)
                    else tracker?.let { t -> // let go while moving: it carries on and slows down
                        t.addMovement(e); t.computeCurrentVelocity(1000, android.view.ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                        val vy = -t.yVelocity
                        if (abs(vy) > android.view.ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                            scroller.fling(0, pickScroll.toInt(), 0, vy.toInt(), 0, 0, 0, maxScroll(g).toInt())
                            flinging = true; postOnAnimation(flingStep)
                        }
                    }
                }
                tracker?.recycle(); tracker = null
                pDown = false
                releaseKeys(e)
            }
            MotionEvent.ACTION_CANCEL -> { ui.removeCallbacks(showTones); stopTones(); tracker?.recycle(); tracker = null; pDown = false; pressed.clear(); altHeld = false; speedHeld = false; invalidate() }
            MotionEvent.ACTION_POINTER_UP -> releaseKeys(e)
        }
        return true
    }

    /** The finger that opened the picker lifts after it is up: let go of the key it was on. */
    private fun releaseKeys(e: MotionEvent) {
        pressed.remove(e.getPointerId(e.actionIndex))
        if (pressed.isEmpty()) { altHeld = false; speedHeld = false }
        invalidate()
    }

    private fun pBases(i: Int) = if (i in pickBases.indices) pickBases[i] else null

    private fun pickerTap(g: PickGeo, x: Float, y: Float) {
        feedback.key()
        when {
            g.more.contains(x, y) -> jump(1)
            g.close.contains(x, y) -> closePicker()
            else -> {
                val i = cellAt(g, x, y)
                if (i >= 0) {
                    listener.onText(pickItems[i])
                    if (picker == Picker.EMOJI) closePicker() // one emoji, and back to the keys; the symbol picker stays open until "close"
                }
            }
        }
        invalidate()
    }

    // ---- hold an emoji that takes a skin tone: the plain one and the five tones in a row to slide to

    private var toneRects: List<RectF> = emptyList()
    private var toneChoices: List<String> = emptyList()
    private var toneIndex = -1

    private val showTones = Runnable {
        val item = pBases(pCell) ?: return@Runnable
        if (item.tones.isEmpty()) return@Runnable
        val g = pickGeo()
        toneChoices = listOf(item.plain) + item.tones
        toneIndex = -1
        val w = dp(40f); val h = dp(46f); val pad = dp(4f)
        val total = toneChoices.size * w + pad * 2
        val c = pCell % pickCols
        val cellL = g.grid.left + c * (g.cw + g.gx); val cellT = g.grid.top + (pCell / pickCols) * g.stride - pickScroll
        val left = (cellL + g.cw / 2f - total / 2f).coerceIn(dp(2f), width - total - dp(2f))
        val top = if (cellT - h - dp(10f) >= dp(2f)) cellT - h - dp(10f) else cellT + g.ch + dp(8f) // above the cell, or below it on the top row
        toneRects = toneChoices.indices.map { RectF(left + pad + it * w, top + pad, left + pad + (it + 1) * w, top + h) }
        feedback.key()
        invalidate()
    }

    private fun stopTones() { ui.removeCallbacks(showTones); toneRects = emptyList(); toneChoices = emptyList(); toneIndex = -1 }

    private fun chooseTone() {
        val item = pBases(pCell)
        val tone = toneIndex
        stopTones()
        if (item != null && tone in 0..5) {
            feedback.key()
            Emoji.remember(context, item, tone)
            tones = Emoji.toneState(context)
            pickItems[pCell] = Emoji.shown(item, tone)
            listener.onText(Emoji.shown(item, tone))
            closePicker()
        }
        invalidate()
    }

    private fun drawTones(canvas: Canvas) {
        if (toneRects.isEmpty()) return
        val l = toneRects.first().left - dp(4f); val t = toneRects.first().top - dp(4f)
        val r = toneRects.last().right + dp(4f); val b = toneRects.first().bottom + dp(4f)
        accentPaint.shader = null
        accentPaint.color = 0xAA000000.toInt(); canvas.drawRoundRect(l, t + dp(3f), r, b + dp(3f), dp(10f), dp(10f), accentPaint)
        accentPaint.shader = LinearGradient(0f, t, 0f, b, th.padTop, th.padBottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(l, t, r, b, dp(10f), dp(10f), accentPaint)
        accentPaint.shader = null
        emojiPaint.textSize = dp(24f)
        toneRects.forEachIndexed { i, rc ->
            if (i == toneIndex) { accentPaint.color = 0xFF2f8fe8.toInt(); canvas.drawRoundRect(rc, dp(7f), dp(7f), accentPaint) }
            canvas.drawText(toneChoices[i], rc.centerX(), rc.centerY() - (emojiPaint.ascent() + emojiPaint.descent()) / 2f, emojiPaint)
        }
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
