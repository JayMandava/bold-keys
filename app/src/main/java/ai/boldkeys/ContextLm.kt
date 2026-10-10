package ai.boldkeys

/** A small language model used only to score words by how well they fit the sentence so far. It never writes text. */
class ContextLm private constructor(private var handle: Long) {
    private external fun nativeScore(h: Long, left: String, cands: Array<String>): FloatArray
    private external fun nativeFree(h: Long)

    /** Log-likelihood of each candidate as the next word after [left]; very low (-99) if it could not be scored. */
    @Synchronized fun score(left: String, cands: List<String>): FloatArray =
        if (handle == 0L) FloatArray(cands.size) { -99f } else nativeScore(handle, left, cands.toTypedArray())

    @Synchronized fun close() { if (handle != 0L) nativeFree(handle); handle = 0 }

    private external fun nativeLoad(path: String, threads: Int): Long

    companion object {
        private val loaded = runCatching { System.loadLibrary("contextlm") }.isSuccess
        fun load(path: String, threads: Int = 2): ContextLm? {
            if (!loaded) return null
            val probe = ContextLm(0)
            val h = runCatching { probe.nativeLoad(path, threads) }.getOrDefault(0L)
            return if (h == 0L) null else ContextLm(h)
        }
    }
}
