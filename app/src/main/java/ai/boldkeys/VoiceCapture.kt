package ai.boldkeys

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** A single bounded recording. PCM lives in memory only and is wiped after local inference. */
internal class VoiceCapture(private val context: Context, private val language: String,
    private val state: (Phase) -> Unit, private val finished: (String?, String?) -> Unit) {
    enum class Phase { RECORDING, TRANSCRIBING }
    private val stop = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val ui = Handler(Looper.getMainLooper())
    @Volatile private var model: Whisper? = null
    @Volatile private var recorder: AudioRecord? = null
    fun stop() { stop.set(true); runCatching { recorder?.stop() } }
    fun cancel() { canceled.set(true); stop.set(true); model?.cancel(); runCatching { recorder?.stop() } }
    fun start() {
        worker.execute {
            val audio = FloatArray(30 * 16000)
            val shorts = ShortArray(2048)
            var peak = 0f
            var count = 0
            var result: String? = null
            var error: String? = null
            try {
                check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
                val buffer = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                check(buffer > 0 && !canceled.get())
                val record = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(buffer, 8192))
                recorder = record
                try {
                    check(record.state == AudioRecord.STATE_INITIALIZED && !canceled.get())
                    record.startRecording()
                    check(record.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                    ui.post { if (!canceled.get()) state(Phase.RECORDING) }
                    while (!stop.get() && count < audio.size) {
                        val n = record.read(shorts, 0, minOf(shorts.size, audio.size - count))
                        if (n <= 0) { check(stop.get()); break }
                        for (i in 0 until n) {
                            val sample = shorts[i] / 32768f
                            audio[count++] = sample; peak = maxOf(peak, kotlin.math.abs(sample))
                        }
                    }
                } finally { runCatching { record.stop() }; record.release(); recorder = null }
                check(!canceled.get())
                if (count < 4000 || peak < 0.005f) {
                    error = "No speech captured. Please try again."
                } else {
                    ui.post { if (!canceled.get()) state(Phase.TRANSCRIBING) }
                    check(VoiceModel.valid(VoiceModel.file(context)))
                    val local = Whisper.load(VoiceModel.file(context).absolutePath)
                    check(local != null)
                    model = local
                    try {
                        if (canceled.get()) local.cancel()
                        val pcm = audio.copyOf(count)
                        try { result = local.transcribe(pcm, language) } finally { pcm.fill(0f) }
                    } finally { local.close(); model = null }
                    if (result.isNullOrBlank()) error = "No transcript available. Please try again."
                }
            } catch (_: Exception) { if (!canceled.get()) error = "Voice typing could not start or transcribe. Check microphone access and the model in Experimental." }
            finally { audio.fill(0f); shorts.fill(0) }
            ui.post { if (!canceled.get()) finished(result, error) }
        }
    }
    private companion object {
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor { task -> Thread(task, "bold-voice") }
    }
}
