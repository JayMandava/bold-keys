package ai.boldkeys

import android.content.Context
import android.os.Bundle
import android.util.Size
import android.view.View
import android.view.inputmethod.InlineSuggestion
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

/** Provider-owned surfaces only: never reads, copies, logs or learns credential content. */
internal class InlineAutofillStrip(context: Context, private val bar: SuggestionBar) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val scroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        visibility = GONE
        addView(chips, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private var generation = 0
    private var pending: List<Candidate>? = null
    init {
        addView(bar, LayoutParams(LayoutParams.MATCH_PARENT, dp(52)))
        addView(scroll, LayoutParams(0, dp(40)))
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val lp = scroll.layoutParams as LayoutParams
        lp.leftMargin = width / 2 + dp(28)
        lp.topMargin = dp(6)
        lp.width = (width - lp.leftMargin - dp(4)).coerceAtLeast(0)
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY))
    }
    fun clear() {
        generation++
        pending = null
        chips.removeAllViews()
        scroll.visibility = GONE
        scroll.scrollTo(0, 0)
        bar.inlineAutofillVisible = false
    }
    internal class Candidate(val spec: InlinePresentationSpec, val inflate: (Size, (View?) -> Unit) -> Unit)

    fun show(suggestions: List<InlineSuggestion>): Boolean = showCandidates(suggestions.map { suggestion ->
        Candidate(suggestion.info.inlinePresentationSpec) { size, callback ->
            suggestion.inflate(context, size, context.mainExecutor) { callback(it) }
        }
    })

    internal fun showCandidates(suggestions: List<Candidate>): Boolean {
        clear()
        if (suggestions.isEmpty()) return false
        if (width == 0) { pending = suggestions.take(8); return true }
        val available = width / 2 - dp(32)
        if (available < dp(48)) return false
        val epoch = generation
        var requested = false
        suggestions.take(8).forEach { suggestion ->
            val spec = suggestion.spec
            val w = minOf(available, spec.maxSize.width)
            val h = minOf(dp(40), spec.maxSize.height)
            if (w < spec.minSize.width || h < spec.minSize.height) return@forEach
            // Placeholders preserve provider order when asynchronous inflation finishes out of order.
            val slot = FrameLayout(context)
            chips.addView(slot, LinearLayout.LayoutParams(w, h))
            try {
                suggestion.inflate(Size(w, h)) { view ->
                    if (generation != epoch) return@inflate
                    if (view == null) { chips.removeView(slot); return@inflate }
                    slot.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    scroll.visibility = VISIBLE
                    bar.inlineAutofillVisible = true
                }
                requested = true
            } catch (_: RuntimeException) {
                // Do not log provider exceptions; they may contain private data.
                chips.removeView(slot)
            }
        }
        return requested
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        pending?.let { showCandidates(it) }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw > 0 && w != oldw) clear()
    }
    override fun onDetachedFromWindow() { clear(); super.onDetachedFromWindow() }

    companion object {
        fun request(context: Context, extras: Bundle): InlineSuggestionsRequest? {
            if (!UiVersions.getVersions(extras).contains(UiVersions.INLINE_UI_VERSION_1)) return null
            val density = context.resources.displayMetrics.density
            val width = context.resources.displayMetrics.widthPixels / 2 - (32 * density).toInt()
            val height = (40 * density).toInt()
            val minWidth = (48 * density).toInt()
            if (width < minWidth) return null
            val styles = UiVersions.newStylesBuilder()
                .addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()
            val spec = InlinePresentationSpec.Builder(Size(minWidth, height), Size(width, height))
                .setStyle(styles).build()
            return InlineSuggestionsRequest.Builder(listOf(spec)).setMaxSuggestionCount(8).build()
        }
    }
}
