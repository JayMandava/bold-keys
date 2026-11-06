package ai.boldkeys

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class SettingsPresentationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activity.get().getSharedPreferences("bk", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun settingsChoicePersistsAndContextualHelpCollapses() {
        val context = activity.get()
        val selected = mutableStateOf(Prefs.layout(context))
        activity.get().setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                ChoiceFlow(Layouts.LAYOUTS, selected.value) { selected.value = it; Prefs.put(context, "layout", it) }
                ExpandableHelp("Layout and accents", "Choose the key order printed on your keyboard.", "Z and Y swap places, as on a German keyboard.")
            }
        } }
        compose.onNodeWithText("QWERTZ").performClick()
        org.junit.Assert.assertEquals("qwertz", Prefs.layout(context))
        compose.onNodeWithContentDescription("Layout and accents: show full instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
            .performClick()
        compose.onNodeWithText("Z and Y swap places, as on a German keyboard.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Layout and accents: hide full instructions").performClick()
        compose.onAllNodesWithText("Z and Y swap places, as on a German keyboard.").assertCountEquals(0)
    }

    @Test fun privacyWarningStaysVisibleWhileDetailsAreCollapsed() {
        val warning = "Optional downloads contact Hugging Face and delivery providers with your IP address and requested model; no audio or typed text is attached."
        activity.get().setContent { MaterialTheme { ExpandableHelp("Privacy details", warning, "Full privacy and network details.") } }
        compose.onNodeWithText(warning).assertIsDisplayed()
        compose.onAllNodesWithText("Full privacy and network details.").assertCountEquals(0)
        compose.onNodeWithContentDescription("Privacy details: show full instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test fun contextualHelpInstancesExpandIndependently() {
        activity.get().setContent { MaterialTheme {
            androidx.compose.foundation.layout.Column {
                ExpandableHelp("Layout and accents", "Layout summary", "Layout details")
                ExpandableHelp("Language behavior", "Language summary", "Language details")
            }
        } }
        compose.onNodeWithContentDescription("Layout and accents: show full instructions").performClick()
        compose.onNodeWithText("Layout details").assertIsDisplayed()
        compose.onAllNodesWithText("Language details").assertCountEquals(0)
        compose.onNodeWithContentDescription("Language behavior: show full instructions")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"))
    }

    @Test fun choiceFlowUsesRadioSemanticsAndPersistsItsSelectedValue() {
        val selected = mutableStateOf("us")
        val options = listOf("us" to "American", "uk" to "British", "fr" to "French", "de" to "German", "es" to "Spanish", "it" to "Italian", "pt" to "Portuguese")
        activity.get().setContent { MaterialTheme { ChoiceFlow(options, selected.value) { selected.value = it } } }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onNode(radio and hasText("American")).assertIsSelected()
        compose.onNode(radio and hasText("British")).performClick()
        compose.onNode(radio and hasText("British")).assertIsSelected()
    }
}
