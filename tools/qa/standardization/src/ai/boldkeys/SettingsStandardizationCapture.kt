package ai.boldkeys

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real SetupActivity, real theme, real navigation. Only navigation/help controls are clicked. */
@RunWith(AndroidJUnit4::class)
class SettingsStandardizationCapture {
    @get:Rule val compose = createAndroidComposeRule<SetupActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val profile = InstrumentationRegistry.getArguments().getString("profile") ?: "normal"
    private val runId = InstrumentationRegistry.getArguments().getString("runId") ?: "manual"
    private val out by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "settings-standardization/$runId/$profile")
            .apply { mkdirs() }
    }
    private val issues = linkedSetOf<String>()
    private val sections = linkedMapOf(
        "Typing" to listOf("Layout and accents", "Language behavior", "Alt key behavior"),
        "Appearance" to emptyList(),
        "Key feel" to listOf("Key feel"),
        "Symbols & emoji" to listOf("Emoji skin tones", "Volume controls", "Symbol picker behavior"),
        "Personal dictionary" to listOf("Learning and safety"),
        "Speed key" to listOf("Speed key behavior", "Shortcut types"),
        "Context model" to listOf("How context scoring works"),
        "Experimental" to listOf("Voice controls", "Screen reader controls"),
        "About" to listOf("Privacy details"),
    )
    private fun list() = compose.onNode(hasScrollToIndexAction())
    private fun slug(text: String) = text.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
    private fun open(text: String) {
        list().performScrollToNode(hasText(text))
        compose.onNodeWithText(text).performScrollTo().performClick()
        compose.waitForIdle()
    }
    private fun top() { list().performScrollToIndex(0); compose.waitForIdle() }

    @Test fun captureAllSettings() {
        out.resolve("started.txt").writeText("profile=$profile\n")
        try {
            sweep("Home", "collapsed")
            sections.forEach { (screen, helps) ->
                open(screen)
                compose.onNodeWithContentDescription("Back").assertIsDisplayed()
                sweep(screen, "collapsed")
                helps.forEach { label ->
                    top()
                    val description = "$label: show full instructions"
                    list().performScrollToNode(hasContentDescription(description))
                    compose.onNodeWithContentDescription(description).performScrollTo().performClick()
                    compose.onNodeWithContentDescription("$label: hide full instructions")
                        .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"))
                }
                if (helps.isNotEmpty()) {
                    sweep(screen, "expanded")
                    // Repeated expansion and independent collapse are exercised for EVERY help.
                    helps.reversed().forEach { label ->
                        top()
                        val description = "$label: hide full instructions"
                        list().performScrollToNode(hasContentDescription(description))
                        compose.onNodeWithContentDescription(description).performScrollTo().performClick()
                        compose.onNodeWithContentDescription("$label: show full instructions")
                            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
                    }
                }
                if (screen == "About") {
                    open("Licenses & credits (available offline)")
                    sampleLicenses()
                }
                compose.onNodeWithContentDescription("Back").performClick()
                compose.onNodeWithText("Bold Keys").assertIsDisplayed()
            }
            out.resolve("coverage-complete.txt").writeText(
                "All ordinary Settings screens swept to scroll end. All 14 help controls expanded and collapsed.\n" +
                "Licenses: 11 section-start samples and final boundary; full composed legal-text layout checked.\n" +
                "Layout issues: ${issues.size}. Visual review still required; license interiors are not exhaustively imaged.\n")
        } finally {
            out.resolve("layout-issues.txt").writeText(issues.joinToString("\n"))
        }
        assertTrue("Text/layout issues (see layout-issues.txt): ${issues.joinToString("; ")}", issues.isEmpty())
    }

    private fun sampleLicenses() {
        val files = listOf(
            "NOTICES.txt", "Apache-2.0.txt", "Nunito-OFL-1.1.txt", "Unicode-V3.txt",
            "llama-MIT.txt", "whisper-MIT.txt", "Whisper-model-MIT.txt",
            "graphics-path-NOTICE.txt", "Kotlin-runtime-NOTICES.txt",
            "CommonVoice-CC0.txt", "CC-BY-SA-4.0.txt",
        )
        files.forEachIndexed { index, file ->
            list().performScrollToIndex(index)
            compose.waitForIdle()
            val expected = instrumentation.targetContext.assets.open("licenses/$file")
                .bufferedReader().use { it.readText() }
            // Match the whole legal text and inspect its whole layout, including off-viewport lines.
            val legalText = compose.onNodeWithText(expected, useUnmergedTree = true)
            legalText.assertExists()
            val layouts = mutableListOf<TextLayoutResult>()
            legalText.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            check(layouts.any { it.layoutInput.text.text == expected }) { "Missing full text layout: $file" }
            layouts.forEach { checkTextLayout("license-section-$index-$file", it) }
            captureViewport("Licenses & credits", "section-start", index)
            out.resolve("license-sections.tsv").appendText("$index\t$file\t${expected.length} characters checked\n")
        }
        // Jump through the remaining last document without taking hundreds of redundant images.
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
        val before = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 1_000_000f) }
        compose.waitForIdle()
        val after = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        check(before == after) { "Licenses final scroll boundary not reached" }
        captureViewport("Licenses & credits", "final-boundary", 0)
        out.resolve("screens.tsv").appendText("Licenses & credits\t11 section starts + final boundary\t12\n")
    }

    /** Wait for a newly invalidated hardware frame to be submitted, not just semantics idle. */
    private fun settleFrame() {
        compose.waitForIdle()
        instrumentation.waitForIdleSync()
        val committed = CountDownLatch(1)
        instrumentation.runOnMainSync {
            val view = compose.activity.window.decorView
            check(view.isAttachedToWindow && view.isHardwareAccelerated) {
                "Window must be attached and hardware accelerated for frame-commit capture"
            }
            check(view.width > 0 && view.height > 0) { "Window has no drawable size" }
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        check(committed.await(10, TimeUnit.SECONDS)) { "Timed out waiting for hardware frame commit" }
    }

    /** PixelCopy reads the committed app-window buffer, including the AndroidView preview. */
    private fun captureWindow(stem: String): Bitmap {
        settleFrame()
        val copied = CountDownLatch(1)
        val status = AtomicInteger(-1)
        lateinit var bitmap: Bitmap
        instrumentation.runOnMainSync {
            val window = compose.activity.window
            bitmap = Bitmap.createBitmap(window.decorView.width, window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(window, bitmap, { result ->
                status.set(result)
                copied.countDown()
            }, Handler(Looper.getMainLooper()))
        }
        // Do not recycle a bitmap while an outstanding PixelCopy might still write into it.
        check(copied.await(10, TimeUnit.SECONDS)) { "PixelCopy timed out for $stem" }
        if (status.get() != PixelCopy.SUCCESS) {
            bitmap.recycle()
            error("PixelCopy failed for $stem with status ${status.get()}")
        }
        out.resolve("capture-method.tsv").appendText("$stem\twindow-pixelcopy\tframe-commit=true\tstatus=${status.get()}\n")
        return bitmap
    }

    private fun checkTextLayout(stem: String, result: TextLayoutResult) {
        val text = result.layoutInput.text.text.replace('\n', ' ').replace('\t', ' ').take(100)
        val lines = 0 until result.lineCount
        val widestLine = lines.maxOfOrNull { result.getLineRight(it) - result.getLineLeft(it) } ?: 0f
        val bottom = lines.maxOfOrNull { result.getLineBottom(it) } ?: 0f
        val ellipsized = lines.any { result.isLineEllipsized(it) }
        val truncated = result.multiParagraph.didExceedMaxLines || ellipsized ||
            (result.lineCount > 0 && result.getLineEnd(result.lineCount - 1) < result.layoutInput.text.length)
        // GetTextLayoutResult for simple Text can rebuild a wider MultiParagraph from the
        // incoming max constraints while retaining the original intrinsic layoutSize.
        // Compare actual line extents, not that synthetic paragraph-container width.
        val widthOverrun = widestLine - result.size.width
        val heightOverrun = bottom - result.size.height
        val meaningful = widthOverrun > 1f || heightOverrun > 1f || truncated
        if (result.hasVisualOverflow || meaningful) {
            out.resolve("text-layout-metrics.tsv").appendText(
                "$stem\tsize=${result.size.width}x${result.size.height}" +
                    "\tparagraph=${result.multiParagraph.width}x${result.multiParagraph.height}" +
                    "\tlineWidth=$widestLine\tlineBottom=$bottom" +
                    "\twidthOverrun=$widthOverrun\theightOverrun=$heightOverrun" +
                    "\tlines=${result.lineCount}\tmaxLines=${result.layoutInput.maxLines}" +
                    "\tellipsized=$ellipsized\ttruncated=$truncated" +
                    "\thasVisualOverflow=${result.hasVisualOverflow}\tfailure=$meaningful\t$text\n")
        }
        if (meaningful) issues += "$stem: text overflow width=$widthOverrun height=$heightOverrun truncated=$truncated: $text"
    }

    private fun captureViewport(screen: String, state: String, page: Int) {
        val stem = "${slug(screen)}-$state-${page.toString().padStart(3, '0')}"
        settleFrame()
        val root = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        val nodes = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        compose.runOnIdle {
            nodes.forEach { node ->
                val bounds = node.boundsInRoot
                val results = mutableListOf<TextLayoutResult>()
                node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
                results.forEach { result -> checkTextLayout(stem, result) }
                if (bounds.width <= 0 || bounds.height <= 0) return@forEach
                // position/size are unclipped, unlike boundsInRoot. Ignore vertical scroll clipping.
                val left = node.positionInRoot.x
                val right = left + node.size.width
                if (left < -1f || right > root.size.width + 1f) {
                    issues += "$stem: horizontal bounds [$left,$right] exceed width ${root.size.width}: " +
                        node.config.getOrNull(SemanticsProperties.Text)?.joinToString().orEmpty().take(100)
                }
            }
        }
        out.resolve("$stem.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString(maxDepth = 100))
        val bitmap = captureWindow(stem)
        out.resolve("$stem.png").outputStream().use { stream ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        bitmap.recycle()
        out.resolve("viewports.tsv").appendText("$screen\t$state\t$page\t$stem.png\n")
    }

    private fun sweep(screen: String, state: String) {
        top()
        settleFrame()
        val topAxis = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        check(topAxis == 0f) { "Expected scroll origin for $screen/$state, got $topAxis" }
        if (screen == "Appearance") compose.onNodeWithText("Keys").assertIsDisplayed()
        if (screen == "Typing") compose.onNodeWithText("Layout").assertIsDisplayed()
        var page = 0
        while (true) {
            captureViewport(screen, state, page)
            val node = list().fetchSemanticsNode()
            val before = node.config[SemanticsProperties.VerticalScrollAxisRange].value()
            val step = node.boundsInRoot.height * 0.80f // 20% overlap so no boundary text is missed.
            list().performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, step) }
            compose.waitForIdle()
            val after = list().fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            if (after == before) break
            page++
            check(page < 1000) { "Scroll did not terminate for $screen/$state" }
        }
        out.resolve("screens.tsv").appendText("$screen\t$state\t${page + 1}\n")
    }
}
