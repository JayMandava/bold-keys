package ai.boldkeys

/** CPU-only local inference. No networking or audio persistence in this class. */
class Whisper private constructor(private var handle: Long) : AutoCloseable {
    private external fun nativeLoad(path: String): Long
    private external fun nativeTranscribe(h: Long, audio: FloatArray, language: String): String?
    private external fun nativeCancel(h: Long)
    private external fun nativeFree(h: Long)
    // Owner serializes transcribe and close; cancel may run concurrently with inference.
    fun transcribe(audio: FloatArray, language: String): String? = nativeTranscribe(handle, audio, language)?.trim()
    @Synchronized fun cancel() { if (handle != 0L) nativeCancel(handle) }
    @Synchronized override fun close() { if (handle != 0L) nativeFree(handle); handle = 0 }
    companion object {
        private val loaded = runCatching { System.loadLibrary("voice") }.isSuccess
        fun load(path: String): Whisper? {
            if (!loaded) return null
            val model = Whisper(0)
            model.handle = model.nativeLoad(path)
            return model.takeIf { it.handle != 0L }
        }
    }
}
