package ai.boldkeys

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Only the optional public model download uses the network. No audio or editor data is accepted. */
object VoiceModel {
    const val SHA256 = "818710568da3ca15689e31a743197b520007872ff9576237bda97bd1b469c3d7"
    const val URL_MODEL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/f281eb45af861ab5e5297d23694b7d46e090c02c/ggml-tiny-q5_1.bin"
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    @Volatile var busy = false; private set
    @Volatile private var canceled = false
    @Volatile private var connection: HttpURLConnection? = null
    private fun directory(c: Context) = File(c.noBackupFilesDir, "voice").apply { mkdirs() }
    fun file(c: Context) = File(directory(c), "tiny-q5_1.bin")
    fun installed(c: Context) = file(c).isFile
    fun valid(f: File): Boolean {
        if (!f.isFile || f.length() !in 30_000_000L..34_000_000L) return false
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(65536)
            while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == SHA256
    }
    @Synchronized fun cancel() { canceled = true; connection?.disconnect() }
    @Synchronized fun delete(c: Context) { if (!busy) file(c).delete() }
    @Synchronized fun download(c: Context, done: (String) -> Unit) {
        if (busy) return
        busy = true; canceled = false
        val app = c.applicationContext
        worker.execute {
            val temp = File(directory(app), "download.part")
            val message = try {
                // A checksum pins the model bytes even if the hosting redirect changes.
                val conn = URL(URL_MODEL).openConnection() as HttpURLConnection
                connection = conn; conn.connectTimeout = 20_000; conn.readTimeout = 20_000
                check(conn.responseCode == 200)
                conn.inputStream.use { input -> temp.outputStream().use { output ->
                    val buf = ByteArray(65536); var total = 0L
                    while (true) {
                        check(!canceled)
                        val n = input.read(buf); if (n < 0) break
                        total += n; check(total <= 34_000_000); output.write(buf, 0, n)
                    }
                } }
                check(!canceled && valid(temp))
                synchronized(this) { check(!canceled); check(temp.renameTo(file(app))) }
                "Model ready. Hold space to speak; release to transcribe and insert."
            } catch (_: Exception) {
                if (canceled) "Download canceled." else "Could not download or verify the model. Please try again."
            } finally { temp.delete(); connection?.disconnect(); connection = null }
            busy = false
            ui.post { done(message) }
        }
    }
}
