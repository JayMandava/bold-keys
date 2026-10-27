package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test

class ImeSettleStateTest {
    private val geometry = ImeSettleState.Frame("editor-1", listOf(0, 602, 720, 1180, 678))

    @Test fun finalLayoutBeforeAnimationEndCannotAuthorizeFirstInjection() {
        val state = ImeSettleState()
        state.prepare("show")
        state.insets(true, 678) // Android dispatches the END layout before the surface has moved.
        state.frame(1, geometry); state.frame(2, geometry)
        assertFalse(state.ready)
        state.progress(true, 678, setOf("show"))
        state.frame(3, geometry); state.frame(4, geometry)
        assertFalse(state.ready)
        state.end("show")
        state.frame(5, geometry)
        assertFalse(state.ready)
        state.frame(6, geometry)
        assertTrue(state.ready)
    }

    @Test fun emptyProgressListDoesNotEndAPreparedAnimation() {
        val state = ImeSettleState()
        state.prepare("show"); state.progress(true, 678, emptySet())
        state.frame(1, geometry); state.frame(2, geometry)
        assertFalse(state.ready)
    }

    @Test fun overlappingAnimationsMustBothEnd() {
        val state = ImeSettleState()
        state.prepare("first"); state.prepare("second"); state.insets(true, 678)
        state.end("first"); state.frame(1, geometry); state.frame(2, geometry)
        assertFalse(state.ready)
        state.end("second"); state.frame(3, geometry); state.frame(4, geometry)
        assertTrue(state.ready)
    }

    @Test fun cancelledShowOrHiddenInsetsCannotAuthorizeInput() {
        val state = ImeSettleState()
        state.prepare("show"); state.insets(false, 0); state.end("show")
        state.frame(1, geometry); state.frame(2, geometry)
        assertFalse(state.ready)
        state.insets(true, 0); state.frame(3, geometry); state.frame(4, geometry)
        assertFalse(state.ready)
    }

    @Test fun alreadyVisibleNonAnimatingImeStillNeedsTwoDistinctFrames() {
        val state = ImeSettleState()
        state.insets(true, 678)
        state.frame(1, geometry); state.frame(1, geometry)
        assertFalse(state.ready)
        state.frame(2, geometry)
        assertTrue(state.ready)
    }

    @Test fun geometryOrEditorChangeResetsReadiness() {
        val state = ImeSettleState()
        state.insets(true, 678); state.frame(1, geometry); state.frame(2, geometry)
        assertTrue(state.ready)
        val rotated = geometry.copy(geometry = listOf(0, 302, 1280, 620, 418))
        state.frame(3, rotated)
        assertFalse(state.ready)
        state.frame(4, rotated)
        assertTrue(state.ready)
        state.frame(5, rotated.copy(editor = "new-field"))
        assertFalse(state.ready)
    }

    @Test fun lostFocusOrBindingAndNewWindowCannotReuseReadiness() {
        val state = ImeSettleState()
        state.insets(true, 678); state.frame(1, geometry); state.frame(2, geometry)
        state.frame(3, null)
        assertFalse(state.ready)
        state.frame(4, geometry)
        assertFalse(state.ready)
        state.frame(5, geometry)
        assertTrue(state.ready)
        state.detach(); state.frame(6, geometry); state.frame(7, geometry)
        assertFalse(state.ready)
    }

    @Test fun newShowOrHideImmediatelyRevokesReadyState() {
        val state = ImeSettleState()
        state.insets(true, 678); state.frame(1, geometry); state.frame(2, geometry)
        assertTrue(state.ready)
        state.prepare("hide")
        assertFalse(state.ready)
        state.insets(false, 0); state.end("hide")
        state.frame(3, geometry); state.frame(4, geometry)
        assertFalse(state.ready)
    }

    @Test fun eachExplicitAwaitRequiresFreshFramesWithoutChangingInsets() {
        val state = ImeSettleState()
        state.insets(true, 678); state.frame(1, geometry); state.frame(2, geometry)
        state.resetFrames()
        assertFalse(state.ready)
        state.frame(3, geometry)
        assertFalse(state.ready)
        state.frame(4, geometry)
        assertTrue(state.ready)
    }
}
