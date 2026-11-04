package ai.boldkeys

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.After
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
import java.io.RandomAccessFile

/** Real Compose pointer and accessibility actions; all files are sparse synthetic placeholders. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class ModelOptionRowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var context: Context
    private val qat get() = ContextModels.catalogue[2]
    private var downloads = 0
    private var cancels = 0
    private var deletes = 0
    private val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)

    @Before fun setup() {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        context = activity.get()
        context.getSharedPreferences("bk_models", Context.MODE_PRIVATE).edit().clear().commit()
        ContextModels.init(context)
        ContextModels.dir.listFiles()?.forEach { it.deleteRecursively() }
        ContextModels.status.clear()
    }
    @After fun tearDown() { activity.pause().stop().destroy() }
    private fun sparse(e: ModelEntry) { RandomAccessFile(ContextModels.file(e), "rw").use { it.setLength(e.bytes) } }
    private fun save(id: String) { context.getSharedPreferences("bk_models", Context.MODE_PRIVATE).edit().putString("selected", id).commit() }
    private fun row(e: ModelEntry) = compose.onNode(radio and hasText(e.name))

    private fun show(entries: List<ModelEntry> = listOf(qat)) {
        val refresh = mutableIntStateOf(0)
        activity.get().setContent {
            MaterialTheme {
                refresh.intValue
                val effective = ContextModels.selected()
                Column(Modifier.selectableGroup()) {
                    entries.forEach { entry ->
                        ModelOptionRow(entry, ContextModels.installed(entry), effective?.id == entry.id,
                            ContextModels.savedSelectionId(), ContextModels.active(entry),
                            details = { Text("Synthetic model details") },
                            onSelect = { ContextModels.selectInstalled(entry); refresh.intValue++ },
                            onDownload = { downloads++ }, onCancel = { cancels++ },
                            onDelete = { deletes++ })
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test fun stalePreferenceAndSoleFallbackAreExplainedWithoutChangingSavedId() {
        sparse(qat); save("custom-stale.gguf"); show()
        row(qat).assertIsSelected().assertIsEnabled().assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                "Saved model unavailable. Using temporarily; tap to save this choice."))
        compose.onNodeWithText("Saved model unavailable. Using temporarily; tap to save this choice.").assertIsDisplayed()
        assertEquals("custom-stale.gguf", ContextModels.savedSelectionId())
        assertEquals(qat.id, ContextModels.selected()?.id)
    }

    @Test fun tappingTheVisibleLabelPersistsTheFallbackAndAnnouncesSavedChoice() {
        sparse(qat); save("custom-stale.gguf"); show()
        compose.onNodeWithText(qat.name, useUnmergedTree = true).performTouchInput { click() }
        row(qat).assertIsSelected().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Saved choice"))
        assertEquals(qat.id, ContextModels.savedSelectionId())
    }

    @Test fun rowHasOneRadioActionAndAtLeast48DpTargetForAccessibilityActivation() {
        sparse(qat); show()
        compose.onAllNodes(radio).assertCountEquals(1)
        val height = row(qat).fetchSemanticsNode().boundsInRoot.height
        assertTrue(height >= 48f * context.resources.displayMetrics.density)
        row(qat).performClick() // accessibility/semantics activation persists even an already-effective fallback
        assertEquals(qat.id, ContextModels.savedSelectionId())
        assertEquals(0, deletes)
    }

    @Test fun fullRowTouchAndIndependentSelectionWorkForAllThreeInstalledModels() {
        ContextModels.catalogue.forEach(::sparse); show(ContextModels.catalogue)
        compose.onAllNodes(radio).assertCountEquals(3)
        for (entry in ContextModels.catalogue) {
            row(entry).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * .7f, height * .8f)) }
            row(entry).assertIsSelected()
            assertEquals(entry.id, ContextModels.savedSelectionId())
            assertEquals(entry.id, ContextModels.selected()?.id)
        }
    }

    @Test fun missingOrBusyRowsAreDisabledAndDownloadCancelNeverSelect() {
        save("custom-retained.gguf"); show()
        row(qat).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Download ${qat.name}").assertHasClickAction()
            .assert(hasAnyAncestor(radio).not()).performTouchInput { click() }
        assertEquals(1, downloads); assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
        compose.runOnIdle { ContextModels.status[qat.id] = Transfer.Downloading(1, qat.bytes) }
        row(qat).assertIsNotEnabled()
        compose.onNodeWithContentDescription("Cancel ${qat.name}").assertHasClickAction()
            .assert(hasAnyAncestor(radio).not()).performClick()
        assertEquals(1, cancels); assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
        assertFalse(ContextModels.selectInstalled(qat))
    }

    @Test fun anInstalledModelWithAnActiveTransferCannotBeSelected() {
        sparse(qat); save("custom-retained.gguf")
        ContextModels.status[qat.id] = Transfer.Verifying
        show()
        row(qat).assertIsNotEnabled().assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Transfer in progress"))
        assertFalse(ContextModels.selectInstalled(qat))
        assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
        compose.onNodeWithContentDescription("Cancel ${qat.name}").assertHasClickAction()
            .assert(hasAnyAncestor(radio).not()).performClick()
        assertEquals(1, cancels)
        assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
    }

    @Test fun deleteButtonDoesNotSelectItsModel() {
        sparse(qat); save("custom-retained.gguf"); show()
        compose.onNodeWithContentDescription("Delete ${qat.name}").performTouchInput { click() }
        assertEquals(1, deletes); assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
    }

    @Test fun aFileRemovedAfterRenderingCannotBePersistedByAStaleRowAction() {
        sparse(qat); save("custom-retained.gguf"); show()
        compose.runOnIdle { ContextModels.file(qat).delete() }
        row(qat).performClick()
        row(qat).assertIsNotEnabled()
        assertEquals("custom-retained.gguf", ContextModels.savedSelectionId())
    }
}
