package ai.boldkeys

/** Pure test-gesture description and clock policy. Never included in release APKs. */
internal object TrackpadGesturePlan {
    const val MIN_GAP_MS = 30L
    const val MAX_GAP_MS = 1_000L
    const val DISTANCE_DP = 28f

    enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP }
    enum class Role { SHIFT, PAD }
    data class Pointer(val id: Int, val role: Role)
    data class Sample(val action: Action, val pointers: List<Pointer>, val padOffsetDp: Float, val actionIndex: Int = 0)
    data class TimedSample(val sample: Sample, val downTime: Long, val eventTime: Long)

    fun samples(select: Boolean): List<Sample> = buildList {
        val pad = Pointer(if (select) 1 else 0, Role.PAD)
        val shift = Pointer(0, Role.SHIFT)
        val moving = if (select) listOf(shift, pad) else listOf(pad)
        add(Sample(Action.DOWN, if (select) listOf(shift) else moving, 0f))
        if (select) add(Sample(Action.POINTER_DOWN, moving, 0f, 1))
        // Seven small physical moves, not one instantaneous 28dp jump that can be extrapolated.
        for (step in 1..7) add(Sample(Action.MOVE, moving, -4f * step))
        add(Sample(Action.MOVE, moving, -DISTANCE_DP)) // stationary endpoint sample
        if (select) {
            add(Sample(Action.POINTER_UP, moving, -DISTANCE_DP, 1))
            add(Sample(Action.UP, listOf(shift), -DISTANCE_DP))
        } else add(Sample(Action.UP, moving, -DISTANCE_DP))
    }

    /** Schedule from the last sampled monotonic event timestamp, never a nominal/catch-up deadline. */
    fun perform(select: Boolean, now: () -> Long, pause: (Long) -> Unit, inject: (TimedSample) -> Unit) {
        var previous: Long? = null
        var downTime = 0L
        for (sample in samples(select)) {
            var time = now()
            val last = previous
            if (last == null) downTime = time else {
                check(time >= last) { "Gesture clock moved backwards" }
                while (time - last < MIN_GAP_MS) {
                    pause(MIN_GAP_MS - (time - last))
                    val next = now()
                    check(next > time) { "Gesture clock did not advance" }
                    time = next
                }
                check(time - last <= MAX_GAP_MS) { "Gesture delivery stalled: ${time - last}ms; not injecting a catch-up event" }
            }
            inject(TimedSample(sample, downTime, time))
            previous = time
        }
    }
}
