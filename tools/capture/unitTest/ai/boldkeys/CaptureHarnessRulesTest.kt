package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test

class CaptureHarnessRulesTest {
    private fun row(size:Int,y:Int=200)=List(4) { i -> CaptureHarnessRules.Bounds(20+i*(size+6),y,20+i*(size+6)+size,y+size) }
    @Test fun swatchesAcceptLayoutAndExpandedTouchSizes() {
        for(size in listOf(40,48,105,126)) assertEquals(listOf(0,1,2,3),CaptureHarnessRules.swatchIndices(row(size),100,500,4))
    }
    @Test fun swatchesRejectMissingAmbiguousClippedOrWrongSectionRows() {
        assertTrue(CaptureHarnessRules.swatchIndices(row(48).take(3),100,500,4).isEmpty())
        assertTrue(CaptureHarnessRules.swatchIndices(row(48)+row(48,300),100,500,4).isEmpty())
        assertTrue(CaptureHarnessRules.swatchIndices(row(48),250,500,4).isEmpty())
        assertTrue(CaptureHarnessRules.swatchIndices(row(48),100,230,4).isEmpty())
    }
    @Test fun verticalGestureCrossesActual26dpQuantumAt420dpi() {
        assertTrue(60/2.625f<26)
        for(direction in listOf(-1,1)) {
            val offsets=CaptureHarnessRules.verticalOffsets(direction)
            assertEquals(0f,offsets.first(),0f);assertEquals(direction*32f,offsets.last(),0f)
            assertTrue(kotlin.math.abs(offsets.last()*2.625f)>26*2.625f)
            assertTrue(offsets.zipWithNext().all { (a,b) -> kotlin.math.abs(b-a)==4f })
        }
    }
    @Test fun falseDeliveryRequiresIndependentlyVerifiedExpectedHandoff() {
        CaptureHarnessRules.requireDelivery(true,null,"own","own")
        CaptureHarnessRules.requireDelivery(false,"target","target","own")
        for(values in listOf(Triple(null,"target","own"),Triple("target","other","own"),Triple("target","target","target"),Triple("target","target",null))) {
            assertThrows(IllegalStateException::class.java) { CaptureHarnessRules.requireDelivery(false,values.first,values.second,values.third) }
        }
    }
    @Test fun ownActivityAndDialogEligibleButImeAndExternalWindowsAreRejected() {
        for(type in listOf(1,2,1003)) assertTrue(CaptureHarnessRules.eligibleWindow(type,"own","own"))
        assertFalse(CaptureHarnessRules.eligibleWindow(2011,"own","own"))
        assertFalse(CaptureHarnessRules.eligibleWindow(2,"external","own"))
    }
    @Test fun failedCleanupDoesNotReplacePrimaryGestureFailure() {
        val primary=IllegalStateException("primary input failure")
        val observed=runCatching {
            try { throw primary } finally { runCatching { error("window gone during cleanup") } }
        }.exceptionOrNull()
        assertSame(primary,observed)
    }
}
