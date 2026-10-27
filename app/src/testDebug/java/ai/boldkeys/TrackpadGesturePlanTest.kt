package ai.boldkeys

import org.junit.Assert.*
import org.junit.Test

class TrackpadGesturePlanTest {
    @Test fun movementKeepsExactly28dpWithSevenSmallMovesAndStationaryEndpoint() {
        for (select in listOf(false, true)) {
            val samples = TrackpadGesturePlan.samples(select)
            val moves = samples.filter { it.action == TrackpadGesturePlan.Action.MOVE }
            assertEquals(listOf(-4f, -8f, -12f, -16f, -20f, -24f, -28f, -28f), moves.map { it.padOffsetDp })
            assertEquals(-TrackpadGesturePlan.DISTANCE_DP, moves.last().padOffsetDp, 0f)
            assertEquals(TrackpadGesturePlan.Action.UP, samples.last().action)
            assertEquals(-28f, samples[samples.lastIndex - 1].padOffsetDp, 0f)
        }
    }

    @Test fun ordinaryDragKeepsOnePadPointerAndExactActionSequence() {
        val samples = TrackpadGesturePlan.samples(false)
        assertEquals(10, samples.size)
        assertEquals(TrackpadGesturePlan.Action.DOWN, samples.first().action)
        assertEquals(TrackpadGesturePlan.Action.UP, samples.last().action)
        assertTrue(samples.all { it.pointers == listOf(TrackpadGesturePlan.Pointer(0, TrackpadGesturePlan.Role.PAD)) && it.actionIndex == 0 })
    }

    @Test fun selectionKeepsShiftZeroAndPadOneUntilTheirOwnPointerUp() {
        val samples = TrackpadGesturePlan.samples(true)
        val shift = TrackpadGesturePlan.Pointer(0, TrackpadGesturePlan.Role.SHIFT)
        val pad = TrackpadGesturePlan.Pointer(1, TrackpadGesturePlan.Role.PAD)
        assertEquals(12, samples.size)
        assertEquals(listOf(shift), samples.first().pointers)
        assertEquals(TrackpadGesturePlan.Action.POINTER_DOWN, samples[1].action)
        assertEquals(1, samples[1].actionIndex)
        assertTrue(samples.drop(1).dropLast(1).all { it.pointers == listOf(shift, pad) })
        assertEquals(TrackpadGesturePlan.Action.POINTER_UP, samples[samples.lastIndex - 1].action)
        assertEquals(1, samples[samples.lastIndex - 1].actionIndex)
        assertEquals(listOf(shift), samples.last().pointers)
    }

    @Test fun actualTimesAreMonotonicAndEndpointHasARealHoldBeforeUp() {
        for (select in listOf(false, true)) {
            var clock = 100L
            val events = mutableListOf<TrackpadGesturePlan.TimedSample>()
            TrackpadGesturePlan.perform(select, { clock }, { clock += it }) { events.add(it) }
            assertTrue(events.all { it.downTime == 100L })
            assertTrue(events.zipWithNext().all { (a, b) -> b.eventTime - a.eventTime == TrackpadGesturePlan.MIN_GAP_MS })
            assertEquals(TrackpadGesturePlan.samples(select), events.map { it.sample })
            assertEquals(30L, events.last().eventTime - events[events.lastIndex - 1].eventTime)
        }
    }

    @Test fun slowInjectionDoesNotCauseCatchUpBursts() {
        var clock = 0L
        val events = mutableListOf<TrackpadGesturePlan.TimedSample>()
        TrackpadGesturePlan.perform(false, { clock }, { clock += it }) {
            events.add(it)
            clock += if (events.size == 3) 95 else 7
        }
        val gaps = events.zipWithNext().map { (a, b) -> b.eventTime - a.eventTime }
        assertTrue(gaps.all { it in TrackpadGesturePlan.MIN_GAP_MS..TrackpadGesturePlan.MAX_GAP_MS })
        assertEquals(95L, gaps[2])
        assertEquals(30L, gaps[3])
    }

    @Test fun earlyWakeStillWaitsForTheActualMinimumGap() {
        var clock = 0L
        val events = mutableListOf<TrackpadGesturePlan.TimedSample>()
        TrackpadGesturePlan.perform(false, { clock }, { clock += minOf(it, 7L) }) { events.add(it) }
        assertTrue(events.zipWithNext().all { (a, b) -> b.eventTime - a.eventTime == 30L })
    }

    @Test fun stalledDeliveryFailsWithoutInjectingAnotherEvent() {
        var clock = 0L
        var count = 0
        assertThrows(IllegalStateException::class.java) {
            TrackpadGesturePlan.perform(false, { clock }, { clock += it }) { count++; clock += 1_001L }
        }
        assertEquals(1, count)
    }

    @Test fun backwardsOrFrozenClockFailsRatherThanFabricatingTimestamps() {
        var clock = 10L
        assertThrows(IllegalStateException::class.java) {
            TrackpadGesturePlan.perform(false, { clock }, { clock += it }) { clock = 9L }
        }
        assertThrows(IllegalStateException::class.java) {
            TrackpadGesturePlan.perform(false, { 10L }, {}) { }
        }
    }
}
