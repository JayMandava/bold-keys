package ai.boldkeys

/** Process readiness only. Encoded frames must be verified after MP4 finalization. */
class RecorderStartupGate(private val prerollMs: Long = 1_000) {
    data class Process(val name: String, val state: String, val startTicks: Long) {
        val live get() = name == "screenrecord" && state in setOf("R", "S", "D", "I")
    }
    private var identity: Long? = null
    private var liveSince: Long? = null
    private var lastTime: Long? = null

    fun observe(nowMs: Long, process: Process?, outputExists: Boolean): Boolean {
        check(lastTime == null || nowMs >= lastTime!!) { "Recorder clock moved backwards" }
        lastTime = nowMs
        if (process?.live != true) {
            liveSince = null
            return false
        }
        check(identity == null || identity == process.startTicks) { "Recorder PID was reused" }
        identity = process.startTicks
        if (!outputExists) {
            liveSince = null
            return false
        }
        if (liveSince == null) liveSince = nowMs
        return nowMs - liveSince!! >= prerollMs
    }

    companion object {
        // /proc/PID/stat: comm can contain spaces/parentheses; fields after its
        // closing ')' start at field 3; starttime is field 22 (index 19).
        fun parseStat(text: String): Process? = runCatching {
            val begin = text.indexOf('(')
            val end = text.lastIndexOf(')')
            require(begin >= 0 && end > begin)
            val fields = text.substring(end + 1).trim().split(Regex("\\s+"))
            Process(text.substring(begin + 1, end), fields[0], fields[19].toLong())
        }.getOrNull()
    }
}
