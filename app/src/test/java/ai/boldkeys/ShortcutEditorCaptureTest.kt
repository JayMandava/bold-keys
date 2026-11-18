package ai.boldkeys

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Optional native-rendered PNGs of the real activity/dialog; no emulated system picker or live call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutEditorCaptureTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<SetupActivity>

    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("bk", 0).edit().clear().commit()
        shadowOf(app).denyPermissions(Manifest.permission.CALL_PHONE)
    }

    @After fun cleanup() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        RuntimeEnvironment.setFontScale(1f)
    }

    private fun capture(call: Boolean, large: Boolean) {
        RuntimeEnvironment.setFontScale(if (large) 2f else 1f)
        val app = RuntimeEnvironment.getApplication()
        Shortcuts.set(app, 'A', Shortcut(if (call) Shortcut.DIRECT_CALL else Shortcut.CONTACT,
            if (call) "+15555550124" else "content://com.android.contacts/contacts/lookup/synthetic/42", "Synthetic Person"))
        controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Speed key"))
        compose.onNodeWithText("Speed key").performScrollTo().performClick()
        compose.onNodeWithText("A").performScrollTo().performClick()
        compose.waitForIdle()
        val prefix = (if (call) "direct-call" else "contact") + (if (large) "-320dp-200pct" else "-360dp-100pct")
        save(prefix + "-top")
        compose.onNodeWithText(if (call) "Enable Direct Call permission" else "Choose contact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        // Scrolled controls are asserted above. Capture only the fresh initial viewport: native
        // Robolectric snapshots can retain stale clipping after a scroll.
    }

    private fun save(name: String) {
        val path = System.getenv("SPEED_KEY_CAPTURE_DIR") ?: return
        compose.waitForIdle()
        compose.runOnUiThread {
            val view = ShadowDialog.getLatestDialog().window!!.decorView
            assertTrue(view.width > 0 && view.height > 0)
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val output = File(path).apply { mkdirs() }
            File(output, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
        File(path, "$name-semantics.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString(maxDepth = Int.MAX_VALUE))
    }

    @Test fun contactAtNormalText() = capture(false, false)
    @Test fun directCallAtNormalText() = capture(true, false)
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun contactAtLargeTextNarrowWidth() = capture(false, true)
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun directCallAtLargeTextNarrowWidth() = capture(true, true)
}
