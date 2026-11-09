package ai.boldkeys

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import org.junit.Assert.*
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
class SettingsStandardizationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    @Before fun setup() { activity = Robolectric.buildActivity(ComponentActivity::class.java).setup() }
    private fun node(text: String) = compose.onNodeWithText(text, useUnmergedTree = true)
    private fun assertJustified(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        node(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(TextAlign.Justify, layouts.single().layoutInput.style.textAlign)
    }
    private fun assertSameEdges(first: String, second: String) {
        val a = node(first).fetchSemanticsNode().boundsInRoot
        val b = node(second).fetchSemanticsNode().boundsInRoot
        assertEquals(a.left, b.left, 0.5f)
        assertEquals(a.right, b.right, 0.5f)
    }

    @Test fun switchDescriptionsUseTheSameFullWidthAsNotesAndStillToggle() {
        val checked = mutableStateOf(false)
        activity.get().setContent { MaterialTheme { Column(Modifier.fillMaxWidth()) {
            Note("Reference explanation")
            SwitchRow("A switch", "Its explanation shares the entire content width.", checked.value) { checked.value = it }
        } } }
        assertJustified("Its explanation shares the entire content width.")
        assertSameEdges("Reference explanation", "Its explanation shares the entire content width.")
        compose.onNodeWithText("A switch").performClick()
        assertTrue(checked.value)
    }

    @Test fun collapsedAndExpandedHelpAndLinksShareTheDescriptionInset() {
        activity.get().setContent { MaterialTheme { Column(Modifier.fillMaxWidth()) {
            Note("Reference explanation")
            ExpandableHelp("Help label", "Always visible summary", "Expanded explanation")
            SettingsLink(onClick = {}) { Text("External link") }
        } } }
        assertJustified("Always visible summary")
        assertSameEdges("Reference explanation", "Always visible summary")
        val left = node("Reference explanation").fetchSemanticsNode().boundsInRoot.left
        assertEquals(left, node("Help label").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        assertEquals(left, node("External link").fetchSemanticsNode().boundsInRoot.left, 0.5f)
        compose.onNodeWithContentDescription("Help label: show full instructions").performClick()
        assertJustified("Expanded explanation")
        assertSameEdges("Reference explanation", "Expanded explanation")
    }

    @Test fun modelDescriptionsUseFullWidthWithoutLosingRadioHierarchy() {
        val entry = ContextModels.catalogue.first()
        activity.get().setContent { MaterialTheme { Column(Modifier.fillMaxWidth()) {
            Note("Reference explanation")
            ModelOptionRow(entry, false, false, null, false,
                details = { SettingsDescription(entry.note) },
                onSelect = {}, onDownload = {}, onCancel = {}, onDelete = {})
        } } }
        assertJustified(entry.note)
        assertSameEdges("Reference explanation", entry.note)
        val title = node(entry.name).fetchSemanticsNode().boundsInRoot
        val description = node(entry.note).fetchSemanticsNode().boundsInRoot
        assertTrue("Radio/title keeps its hierarchy", title.left > description.left)
    }
}
