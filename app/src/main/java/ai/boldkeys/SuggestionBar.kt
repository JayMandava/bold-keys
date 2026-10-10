package ai.boldkeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The row above the keys: the best suggestions on either side and, in the middle, the trackpad. Dragging on the trackpad moves the text
 * cursor (sideways by letters, up and down by lines); holding a shift key while dragging selects, as on the real keyboard.
 */
class SuggestionBar(context: Context, private val feedback: Feedback, private val listener: Listener, private val shiftHeld: () -> Boolean) : View(context) {
    interface Listener {
        fun onPick(index: Int)
        fun onMove(dx: Int, dy: Int, select: Boolean)
    }

    private val d = resources.displayMetrics.density
    private fun dp(v: Float) = v * d

    private var th = KeyTheme(true)
    fun applyTheme(dark: Boolean) { th = KeyTheme(dark); invalidate() }

    private var words: List<String> = emptyList()
    fun setSuggestions(w: List<String>) { if (w != words) { words = w; invalidate() } }

    private val face: Typeface = runCatching {
        Typeface.Builder(context.assets, "Nunito.ttf").setFontVariationSettings("'wght' 700").build()
    }.getOrNull() ?: Typeface.DEFAULT_BOLD
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = face; color = 0xFFF4F6F8.toInt() }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val pad = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), dp(52f).toInt())
    }

    private fun padRect() = pad.apply {
        val s = dp(40f); val cx = width / 2f; val cy = height / 2f + dp(2.5f) // a touch below the middle, so the gap to the keys is not tighter than the one above
        set(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
    }

    private var dragging = false
    private var pressed = false
    private var lastX = 0f; private var lastY = 0f
    private var accX = 0f; private var accY = 0f
    private var downSlot = -1
    private var travel = 0f
    private var downAt = 0L

    private fun slotAt(x: Float, y: Float): Int {
        val p = padRect()
        if (p.contains(x, y)) return -2
        return if (x < p.left) 0 else if (x > p.right) 1 else -1
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downSlot = slotAt(e.x, e.y)
                if (downSlot == -2) { dragging = true; pressed = true; lastX = e.x; lastY = e.y; accX = 0f; accY = 0f; travel = 0f; downAt = e.eventTime; feedback.buzz("ms3"); invalidate() }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                accX += e.x - lastX; accY += e.y - lastY; travel += abs(e.x - lastX) + abs(e.y - lastY); lastX = e.x; lastY = e.y
                val stepX = dp(13f); val stepY = dp(26f)
                var dx = (accX / stepX).toInt(); var dy = (accY / stepY).toInt()
                // a mostly sideways or mostly vertical swipe stays on its axis
                if (abs(accX) > abs(accY) * 1.4f) dy = 0 else if (abs(accY) > abs(accX) * 1.4f) dx = 0
                if (dx != 0 || dy != 0) {
                    accX -= dx * stepX; accY -= dy * stepY
                    feedback.buzz("ms3")
                    listener.onMove(dx, dy, shiftHeld())
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && downSlot >= 0 && slotAt(e.x, e.y) == downSlot && downSlot < words.size) { feedback.key(); listener.onPick(downSlot) }
                // a quick tap on the pad, without dragging, takes the best suggestion (as a click on the original's trackpad did)
                if (dragging && travel < dp(8f) && e.eventTime - downAt < 350 && words.isNotEmpty()) { feedback.key(); listener.onPick(0) }
                dragging = false; pressed = false; invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; pressed = false; invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(th.bg)
        val p = padRect()
        // the words
        val slotW = p.left - dp(8f)
        for (i in 0 until 2) {
            val w = words.getOrNull(i) ?: continue
            text.typeface = if (i == 0) face else Typeface.create(face, Typeface.NORMAL)
            text.color = if (i == 0) th.barText else th.barText2
            text.textSize = dp(17f)
            while (text.measureText(w) > slotW - dp(16f) && text.textSize > dp(10f)) text.textSize -= dp(0.5f)
            val cx = if (i == 0) slotW / 2f + dp(2f) else p.right + (width - p.right) / 2f - dp(2f) + dp(4f)
            canvas.drawText(w, cx, height / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
        // the trackpad: a silver square ring around a dark, slightly domed centre
        val r = RectF(p)
        fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFFf2f4f6.toInt(), 0xFF70747b.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(r, dp(9f), dp(9f), fill)
        val inner = RectF(r).apply { inset(dp(3.2f), dp(3.2f)) }
        fill.shader = LinearGradient(0f, inner.top, 0f, inner.bottom, if (pressed) 0xFF0c0e10.toInt() else 0xFF1c2127.toInt(), 0xFF07080a.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, dp(6.5f), dp(6.5f), fill)
        fill.shader = RadialGradient(inner.centerX() - inner.width() * 0.12f, inner.top + inner.height() * 0.3f, inner.width() * 0.7f, if (pressed) 0x14FFFFFF else 0x33FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(inner, dp(6.5f), dp(6.5f), fill)
        fill.shader = null
        line.strokeWidth = dp(1f); line.color = 0x99000000.toInt()
        canvas.drawRoundRect(inner, dp(6.5f), dp(6.5f), line)
        // faint dividers either side of the pad
        line.strokeWidth = dp(1f); line.color = th.divider
        canvas.drawLine(p.left - dp(6f), dp(12f), p.left - dp(6f), height - dp(12f), line)
        canvas.drawLine(p.right + dp(6f), dp(12f), p.right + dp(6f), height - dp(12f), line)
    }
}
