package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test

class RecorderStartupGateTest {
    private val live = RecorderStartupGate.Process("screenrecord", "S", 42)

    @Test fun staticScreenWithZeroBytesCanStartAfterStablePreroll() {
        val gate = RecorderStartupGate()
        // Existing output may remain 0/4096 bytes until muxer finalization.
        // The gate intentionally takes existence, never an encoded-byte count.
        assertFalse(gate.observe(0, live, true))
        assertFalse(gate.observe(999, live, true))
        assertTrue(gate.observe(1000, live, true))
    }

    @Test fun missingOutputNeverStartsEvenWithLiveProcess() {
        val gate = RecorderStartupGate()
        for (t in 0L..10_000L step 250) assertFalse(gate.observe(t, live, false))
    }

    @Test fun absentOrWrongProcessNeverStartsEvenWithLargeOutput() {
        for (process in listOf(null, live.copy(name = "sh"), live.copy(state = "Z"),
                live.copy(state = "X"), live.copy(state = "T"))) {
            val gate = RecorderStartupGate()
            assertFalse(gate.observe(0, process, true))
            assertFalse(gate.observe(10_000, process, true))
        }
    }

    @Test fun processDisappearanceAndMissingOutputResetPreroll() {
        for (missingProcess in listOf(true, false)) {
            val gate = RecorderStartupGate()
            assertFalse(gate.observe(0, live, true))
            assertFalse(gate.observe(900, if (missingProcess) null else live, missingProcess))
            assertFalse(gate.observe(1000, live, true))
            assertTrue(gate.observe(2000, live, true))
        }
    }

    @Test fun reusedPidFailsInsteadOfSignallingOrAcceptingAnotherRecorder() {
        val gate = RecorderStartupGate()
        gate.observe(0, live, true)
        assertThrows(IllegalStateException::class.java) { gate.observe(1000, live.copy(startTicks = 43), true) }
    }

    @Test fun backwardsClockFails() {
        val gate = RecorderStartupGate()
        gate.observe(100, live, true)
        assertThrows(IllegalStateException::class.java) { gate.observe(99, live, true) }
    }

    @Test fun statParserUsesField22AndRejectsMalformedInput() {
        val fields = listOf("S") + List(18) { "0" } + "98765" + listOf("999", "0")
        assertEquals(live.copy(startTicks = 98765), RecorderStartupGate.parseStat("123 (screenrecord) " + fields.joinToString(" ")))
        assertEquals("name (with spaces)", RecorderStartupGate.parseStat("123 (name (with spaces)) " + fields.joinToString(" "))?.name)
        for (bad in listOf("", "cat: permission denied", "123 (screenrecord) S 0", "123 screenrecord S")) {
            assertNull(RecorderStartupGate.parseStat(bad))
        }
    }
}
