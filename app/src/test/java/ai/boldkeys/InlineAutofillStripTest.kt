package ai.boldkeys

import android.os.Bundle
import android.util.Size
import android.view.View
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class InlineAutofillStripTest {
    private val context = RuntimeEnvironment.getApplication()
    private val bar = SuggestionBar(context, Feedback(context), object : SuggestionBar.Listener {
        override fun onPick(index: Int) = Unit
        override fun onMove(dx: Int, dy: Int, select: Boolean) = Unit
    }) { false }
    private val strip = InlineAutofillStrip(context, bar)
    private val callbacks = mutableListOf<(View?) -> Unit>()
    private fun dp(n: Int) = (n * context.resources.displayMetrics.density).toInt()
    private fun layout(width: Int = 360) {
        strip.measure(View.MeasureSpec.makeMeasureSpec(dp(width), View.MeasureSpec.EXACTLY), 0)
        strip.layout(0, 0, strip.measuredWidth, strip.measuredHeight)
    }
    private fun candidate() = InlineAutofillStrip.Candidate(
        InlinePresentationSpec.Builder(Size(dp(48), dp(40)), Size(dp(200), dp(40))).build()
    ) { size, callback -> assertTrue(size.width <= dp(148)); callbacks += callback }

    @Test fun fixedHeightAndSuggestionsRestoreAfterClear() {
        layout(); bar.setSuggestions(listOf("one", "two"))
        assertEquals(dp(52), strip.measuredHeight)
        assertTrue(strip.showCandidates(listOf(candidate())))
        assertFalse(bar.inlineAutofillVisible)
        callbacks[0](View(context))
        assertTrue(bar.inlineAutofillVisible)
        strip.clear(); assertFalse(bar.inlineAutofillVisible)
        assertEquals(dp(52), strip.measuredHeight)
    }
    @Test fun staleInflationCannotReappearAfterClearOrNewResponse() {
        layout(); strip.showCandidates(listOf(candidate())); strip.clear()
        callbacks[0](View(context)); assertFalse(bar.inlineAutofillVisible)
        strip.showCandidates(listOf(candidate())); strip.showCandidates(emptyList())
        callbacks[1](View(context)); assertFalse(bar.inlineAutofillVisible)
    }
    @Test fun multipleEntriesAndNullProviderResult() {
        layout(); assertTrue(strip.showCandidates(listOf(candidate(), candidate(), candidate())))
        callbacks[2](View(context)); callbacks[0](null); callbacks[1](View(context))
        assertTrue(bar.inlineAutofillVisible)
        assertFalse(strip.showCandidates(emptyList())); assertFalse(bar.inlineAutofillVisible)
    }
    @Test fun narrowWidthAndUnsupportedProviderKeepPlatformFallback() {
        layout(120); assertFalse(strip.showCandidates(listOf(candidate())))
        assertNull(InlineAutofillStrip.request(context, Bundle()))
    }
    @Test fun supportedRequestAdvertisesBoundedStrip() {
        val extras = UiVersions.newStylesBuilder().addStyle(InlineSuggestionUi.newStyleBuilder().build()).build()
        val request = InlineAutofillStrip.request(context, extras)!!
        assertEquals(8, request.maxSuggestionCount)
        assertEquals(dp(40), request.inlinePresentationSpecs[0].maxSize.height)
    }
    @Test fun earlyResponseWaitsForLayoutAndClearCancelsIt() {
        assertTrue(strip.showCandidates(listOf(candidate())))
        assertTrue(callbacks.isEmpty())
        layout(); assertEquals(1, callbacks.size)
        callbacks[0](View(context)); assertTrue(bar.inlineAutofillVisible)
    }
    @Test fun clearCancelsUnlaidOutResponse() {
        strip.showCandidates(listOf(candidate())); strip.clear(); layout()
        assertTrue(callbacks.isEmpty()); assertFalse(bar.inlineAutofillVisible)
    }
    @Test fun resizeInvalidatesPendingProviderSurfaces() {
        layout(); strip.showCandidates(listOf(candidate())); layout(320)
        callbacks[0](View(context)); assertFalse(bar.inlineAutofillVisible)
    }
}
