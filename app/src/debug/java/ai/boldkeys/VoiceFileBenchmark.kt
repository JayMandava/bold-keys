package ai.boldkeys

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.os.Debug
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug-only, file-input verification. Never enables an IME or opens the microphone. */
class VoiceFileBenchmark : Instrumentation() {
    private lateinit var options: Bundle
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        options = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        val result = Bundle()
        var qat: ContextLm? = null
        try {
            check(targetContext.packageName == "ai.boldkeys.voice.review") { "Requires isolated voice review package" }
            val directory = File(targetContext.filesDir, "voice-benchmark")
            val modelFile = File(directory, "tiny-q5_1.bin")
            check(VoiceModel.valid(modelFile)) { "Baseline model checksum failed" }
            val beforeQat = Debug.getPss()
            if (options.getString("qat") == "true") {
                ContextModels.init(targetContext)
                val entry = ContextModels.entries().first { it.id == "gemma3-270m-qat-q4" }
                val installed = ContextModels.file(entry)
                installed.parentFile?.mkdirs()
                File(directory, "qat.gguf").copyTo(installed, overwrite = true)
                check(ContextModels.selectInstalled(entry) && ContextModels.savedSelectionId() == entry.id)
                qat = checkNotNull(ContextLm.load(installed.absolutePath)) { "QAT did not load" }
                check(qat.score("Please send the", listOf("report", "coffee")).all { it.isFinite() && it > -99f })
            }
            val withQat = Debug.getPss()
            val fixtures = JSONArray(File(directory, "fixtures.json").readText())
            val rows = JSONArray()
            val repeats = options.getString("repeats")?.toInt() ?: 2
            require(repeats in 1..5)
            repeat(repeats) { iteration ->
                for (index in 0 until fixtures.length()) {
                    val fixture = fixtures.getJSONObject(index)
                    val filename = fixture.getString("file")
                    require(File(filename).name == filename)
                    val bytes = File(directory, filename).readBytes()
                    require(bytes.size in 3200..960000 && bytes.size % 2 == 0)
                    val source = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    val audio = FloatArray(source.remaining()) { source.get() / 32768f }
                    val start = SystemClock.elapsedRealtimeNanos()
                    check(VoiceModel.valid(modelFile))
                    val validated = SystemClock.elapsedRealtimeNanos()
                    val model = checkNotNull(Whisper.load(modelFile.absolutePath))
                    val loaded = SystemClock.elapsedRealtimeNanos()
                    var text: String?
                    try { text = model.transcribe(audio, fixture.getString("language")) }
                    finally { model.close(); audio.fill(0f); bytes.fill(0) }
                    val end = SystemClock.elapsedRealtimeNanos()
                    rows.put(JSONObject().put("id", fixture.getString("id")).put("repeat", iteration)
                        .put("text", text).put("validation_ms", (validated - start) / 1e6)
                        .put("load_ms", (loaded - validated) / 1e6)
                        .put("compute_ms", (end - start) / 1e6).put("pss_kb", Debug.getPss()))
                }
            }
            // A canceled handle must not produce text. Reopening must still work.
            checkNotNull(Whisper.load(modelFile.absolutePath)).use { model ->
                model.cancel()
                val silent = FloatArray(16000)
                try { check(model.transcribe(silent, "en") == null) }
                finally { silent.fill(0f) }
            }
            checkNotNull(Whisper.load(modelFile.absolutePath)).use { }
            File(directory, "result.json").writeText(JSONObject().put("qat_loaded", qat != null)
                .put("qat_selected", options.getString("qat") == "true")
                .put("before_qat_pss_kb", beforeQat).put("with_qat_pss_kb", withQat)
                .put("scope", "validation/load/JNI/inference/free; no capture or editor insertion")
                .put("cancellation_check", "passed").put("rows", rows).toString(2))
            result.putString("result", "passed")
            finish(Activity.RESULT_OK, result)
        } catch (error: Exception) {
            result.putString("result", "failed: ${error.javaClass.simpleName}: ${error.message}")
            finish(Activity.RESULT_CANCELED, result)
        } finally { qat?.close() }
    }
}
