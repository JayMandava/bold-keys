package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test

class CaptureImeGeometryTest {
    private val ime=CaptureImeGeometry.Rect(0,1031,1080,1920)
    private val bar=CaptureImeGeometry.Rect(0,1031,1080,1167)
    private val keyboard=CaptureImeGeometry.Rect(0,1167,1080,1817)
    private val dialogFrame=ImeSettleState.Frame("focused-dialog-editor",listOf(0,0,889,1031,1080,1920))
    @Test fun visibleZeroClientInsetIsValidWithIndependentRealImeGeometry() {
        assertTrue(CaptureImeGeometry.consistentInsets(true,0,0))
        assertTrue(CaptureImeGeometry.stableLayoutCandidate(1080,1920,ime,keyboard,bar))
        val old=ImeSettleState();old.insets(true,0);old.frame(1,dialogFrame);old.frame(2,dialogFrame)
        assertFalse("Old positive-client-inset assumption reproduces rejection",old.ready)
        val state=CaptureImeSettleState();state.insets(true,0);state.frame(1,dialogFrame)
        assertFalse(state.ready);state.frame(2,dialogFrame);assertTrue(state.ready)
    }
    @Test fun visible889ClientInsetContinuesToWork() {
        assertTrue(CaptureImeGeometry.consistentInsets(true,889,889))
        val state=CaptureImeSettleState();state.insets(true,889);state.frame(1,dialogFrame);state.frame(2,dialogFrame)
        assertTrue(state.ready)
    }
    @Test fun neverBorrowBackgroundWindows889ForZeroInsetDialog() {
        assertFalse(CaptureImeGeometry.consistentInsets(true,0,889))
        assertFalse(CaptureImeGeometry.consistentInsets(true,889,0))
        assertFalse(CaptureImeGeometry.consistentInsets(false,0,0))
        assertFalse(CaptureImeGeometry.consistentInsets(true,-1,-1))
    }
    @Test fun zeroInsetWithoutRealBoundFrameNeverReady() {
        val state=CaptureImeSettleState();state.insets(true,0)
        repeat(5) { state.frame(it.toLong(),null) };assertFalse(state.ready)
    }
    @Test fun actualOffscreenOrWrongWindowGeometryStillFails() {
        assertFalse(CaptureImeGeometry.stableLayoutCandidate(1080,1920,ime,keyboard.copy(bottom=2000),bar))
        assertFalse(CaptureImeGeometry.stableLayoutCandidate(1080,1920,ime,keyboard,bar.copy(top=900)))
        assertFalse(CaptureImeGeometry.stableLayoutCandidate(1080,1920,ime.copy(bottom=2000),keyboard,bar))
        assertFalse(CaptureImeGeometry.stableLayoutCandidate(1080,1920,ime,keyboard.copy(right=300),bar))
    }
    @Test fun animationAndGeometryIdentityChangesStillBlockReadiness() {
        val state=CaptureImeSettleState();state.insets(true,0);state.prepare("animation")
        state.frame(1,dialogFrame);state.frame(2,dialogFrame);assertFalse(state.ready)
        state.end("animation");state.frame(3,dialogFrame);assertFalse(state.ready)
        state.frame(4,dialogFrame.copy(editor="different-client"));assertFalse(state.ready)
        state.frame(5,dialogFrame.copy(editor="different-client"));assertTrue(state.ready)
        state.frame(6,dialogFrame.copy(editor="different-client",geometry=listOf(1)));assertFalse(state.ready)
    }
    @Test fun duplicateFrameAndHiddenImeNeverEstablishReadiness() {
        val state=CaptureImeSettleState();state.insets(true,0);state.frame(1,dialogFrame);state.frame(1,dialogFrame)
        assertFalse(state.ready);state.insets(false,0);state.frame(2,dialogFrame);state.frame(3,dialogFrame)
        assertFalse(state.ready)
    }
}
