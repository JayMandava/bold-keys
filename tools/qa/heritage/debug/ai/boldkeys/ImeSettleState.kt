package ai.boldkeys

/** Pure QA state only; not part of release APKs or used by the product keyboard. */
internal class ImeSettleState {
    private val prepared = mutableSetOf<Any>()
    private var running = emptySet<Any>()
    private var visible = false
    private var bottom = 0
    private var previous: Frame? = null
    private var previousFrameTime = Long.MIN_VALUE
    private var stableFrames = 0

    data class Frame(val editor: String, val geometry: List<Int>)

    var ready = false
        private set

    fun prepare(animation: Any) { prepared.add(animation); resetFrames() }
    fun end(animation: Any) {
        prepared.remove(animation)
        running = running - animation
        resetFrames()
    }

    fun insets(imeVisible: Boolean, imeBottom: Int) {
        if (visible != imeVisible || bottom != imeBottom) resetFrames()
        visible = imeVisible
        bottom = imeBottom
    }

    fun progress(imeVisible: Boolean, imeBottom: Int, animations: Set<Any>) {
        if (running != animations) resetFrames()
        running = animations
        insets(imeVisible, imeBottom)
    }

    /** Only distinct actual frame timestamps can establish stable, bound client geometry. */
    fun frame(frameTimeNanos: Long, sample: Frame?) {
        if (frameTimeNanos <= previousFrameTime) return
        previousFrameTime = frameTimeNanos
        if (!visible || bottom <= 0 || prepared.isNotEmpty() || running.isNotEmpty() || sample == null) {
            resetFrames()
            return
        }
        if (sample == previous) stableFrames++ else {
            previous = sample
            stableFrames = 1
        }
        ready = stableFrames >= 2
    }

    fun resetFrames() {
        previous = null
        stableFrames = 0
        ready = false
    }

    fun detach() {
        prepared.clear(); running = emptySet(); visible = false; bottom = 0
        previousFrameTime = Long.MIN_VALUE
        resetFrames()
    }

    fun diagnostic() = "visible=$visible bottom=$bottom prepared=${prepared.size} running=${running.size} stableFrames=$stableFrames ready=$ready"
}
