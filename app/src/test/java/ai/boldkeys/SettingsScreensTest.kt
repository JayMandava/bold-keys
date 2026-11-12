package ai.boldkeys

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

/** Real navigation and persisted settings; no permission requests or model transfers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsScreensTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().getSharedPreferences("bk", Context.MODE_PRIVATE).edit().clear().commit()
        activity.get().setContent { MaterialTheme { SettingsApp() } }
        compose.waitForIdle()
    }

    private fun open(title: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(title))
        compose.onNodeWithText(title).performScrollTo().performClick()
    }

    @Test fun typingNavigationPersistsLayoutAndKeepsHelpIndependent() {
        open("Typing")
        compose.onNodeWithText("QWERTZ").performScrollTo().performClick()
        assertEquals("qwertz", Prefs.layout(activity.get()))
        compose.onNodeWithContentDescription("Layout and accents: show full instructions")
            .performScrollTo().performClick()
        compose.onNode(hasText("Z and Y swap places", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Language behavior: show full instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onNodeWithContentDescription("Layout and accents: hide full instructions")
            .performScrollTo().performClick()
        compose.onAllNodes(hasText("Z and Y swap places", substring = true)).assertCountEquals(0)
    }

    @Test fun appearanceOffersEveryColourAndPreservesIndependentBacklightChoice() {
        Prefs.put(activity.get(), "backlight", "on")
        Prefs.put(activity.get(), "backlightColor", "amber")
        open("Appearance")
        for ((id, label) in KeyColour.choices) {
            compose.onNodeWithText(label).performScrollTo().performClick()
            compose.onNodeWithText(label).assertIsSelected()
            assertEquals(id, Prefs.theme(activity.get()))
            assertEquals("on", Prefs.backlightMode(activity.get()))
            assertEquals("amber", Prefs.backlightColor(activity.get()))
        }
        compose.onNodeWithText("White & Gold").performScrollTo().performClick()
        assertEquals(false, Prefs.isDark(activity.get()))
        compose.onNode(hasText("Choose Black, Red or Black & Gold", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Red").performScrollTo().performClick()
        compose.onNodeWithText("Glows all the time.").performScrollTo().assertIsDisplayed()
        compose.onAllNodes(hasText("Choose Black, Red or Black & Gold", substring = true))
            .assertCountEquals(0)
    }

    @Test fun aboutDisclosesNetworkExceptionBeforePrivacyHelpIsExpanded() {
        open("About")
        compose.onNode(hasText("Optional downloads contact Hugging Face", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Privacy details: show full instructions")
            .assertTextEquals("Privacy details")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        compose.onAllNodes(hasText("Model downloads contact Hugging Face over HTTPS only when you tap Download", substring = true))
            .assertCountEquals(0)
        compose.onNodeWithContentDescription("Privacy details: show full instructions")
            .performScrollTo().performClick()
        compose.onNode(hasText("Model downloads contact Hugging Face over HTTPS only when you tap Download", substring = true))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun experimentalKeepsAudioAndDownloadWarningsVisibleWithoutExpandingHelp() {
        open("Experimental")
        compose.onNode(hasText("There is no online recognition fallback", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("Only the model download contacts Hugging Face", substring = true))
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Voice controls: show full instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
        assertEquals(false, Prefs.voiceTyping(activity.get()))
    }
}
