package ai.boldkeys

import android.Manifest
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
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

/** Reachability checks for the real editor. Authoritative pixels use the emulator QA harness. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ShortcutEditorLayoutTest {
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

    private fun checkLayout(call: Boolean, large: Boolean) {
        RuntimeEnvironment.setFontScale(if (large) 2f else 1f)
        val app = RuntimeEnvironment.getApplication()
        Shortcuts.set(app, 'A', Shortcut(if (call) Shortcut.DIRECT_CALL else Shortcut.CONTACT,
            if (call) "+15555550124" else "content://com.android.contacts/contacts/lookup/synthetic/42", "Synthetic Person"))
        controller = Robolectric.buildActivity(SetupActivity::class.java).setup()
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Speed key"))
        compose.onNodeWithText("Speed key").performScrollTo().performClick()
        compose.onNodeWithText("A").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText(if (call) "Enable Direct Call permission" else "Choose contact").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test fun contactAtNormalText() = checkLayout(false, false)
    @Test fun directCallAtNormalText() = checkLayout(true, false)
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun contactAtLargeTextNarrowWidth() = checkLayout(false, true)
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun directCallAtLargeTextNarrowWidth() = checkLayout(true, true)
}
