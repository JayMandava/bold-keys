package ai.boldkeys

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateMapOf
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** One model the user can pick for judging words in context. */
class ModelEntry(
    val id: String, val name: String, val note: String, val license: String,
    val file: String, val url: String, val sha256: String, val bytes: Long, val custom: Boolean = false,
)

sealed interface Transfer {
    class Downloading(val done: Long, val total: Long) : Transfer
    object Verifying : Transfer
    class Failed(val reason: String) : Transfer
}

/**
 * The models on the phone, which one is chosen, and how new ones arrive. Downloading is the only thing in the app that uses the network:
 * it happens only when the user taps Download, and each file is checked against its SHA-256 before it is kept.
 */
object ContextModels {
    private const val HF = "https://huggingface.co"
    private const val GEMMA = "Gemma terms"

    val catalogue = listOf(
        ModelEntry(
            "gemma3-270m-q4", "Gemma 3 270M (compact)", "Recommended. Small and quick: about 0.05 s to judge a word on this phone.", GEMMA,
            "gemma-3-270m-it-Q4_0.gguf", "$HF/unsloth/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q4_0.gguf",
            "e479ea2962bdcdc7e6321b91148b9ac2f516f649e0921412561d4936aadef158", 241_574_944,
        ),
        ModelEntry(
            "gemma3-270m-q8", "Gemma 3 270M (precise)", "Same model at higher precision. Slightly more accurate, a little larger.", GEMMA,
            "gemma-3-270m-it-Q8_0.gguf", "$HF/unsloth/gemma-3-270m-it-GGUF/resolve/main/gemma-3-270m-it-Q8_0.gguf",
            "d156a5159f2f79c1b1d53c7c1cc20f1ff28ab8d00f17a292620aad13399b9698", 291_546_144,
        ),
    )

    private lateinit var app: Context
    private val sp get() = app.getSharedPreferences("bk_models", Context.MODE_PRIVATE)
    val dir get() = File(app.filesDir, "models").apply { mkdirs() }
    val status = mutableStateMapOf<String, Transfer>()

    fun init(c: Context) { app = c.applicationContext }

    fun entries(): List<ModelEntry> = catalogue + customs()
    fun file(e: ModelEntry) = File(dir, e.file)
    fun installed(e: ModelEntry) = file(e).let { it.exists() && (e.custom || it.length() == e.bytes) }

    /** The chosen model, or the first one on the phone, or null when none is installed. */
    fun selected(): ModelEntry? {
        val all = entries()
        val chosen = all.firstOrNull { it.id == sp.getString("selected", null) }
        return if (chosen != null && installed(chosen)) chosen else all.firstOrNull { installed(it) }
    }
    fun select(e: ModelEntry) = sp.edit().putString("selected", e.id).apply()

    fun delete(e: ModelEntry) {
        file(e).delete()
        if (e.custom) saveCustoms(customs().filter { it.id != e.id })
    }

    // ---- models the user brought

    private fun customs(): List<ModelEntry> = sp.getString("custom", "")!!.lines().filter { it.isNotBlank() }.map {
        val p = it.split('\t')
        ModelEntry("custom-${p[0]}", p[1], "Imported from a file.", "Your own", p[0], "", "", 0, custom = true)
    }
    private fun saveCustoms(l: List<ModelEntry>) = sp.edit().putString("custom", l.joinToString("\n") { "${it.file}\t${it.name}" }).apply()

    /** Copies a .gguf the user picked into the app. Run off the main thread. */
    fun import(uri: Uri, displayName: String): ModelEntry {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val tmp = File(dir, "$safe.tmp")
        app.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
        tmp.renameTo(File(dir, safe))
        val e = ModelEntry("custom-$safe", displayName.substringBeforeLast('.'), "Imported from a file.", "Your own", safe, "", "", 0, custom = true)
        saveCustoms(customs().filter { it.id != e.id } + e)
        select(e)
        return e
    }

    // ---- downloading

    @Volatile private var cancelled = HashSet<String>()

    fun active(e: ModelEntry) = status[e.id].let { it is Transfer.Downloading || it == Transfer.Verifying }
    fun cancel(e: ModelEntry) { cancelled.add(e.id) }

    /** Fetches [e] on its own thread. [onDone] runs when it is installed and verified. */
    fun download(e: ModelEntry, wifiOnly: Boolean, onDone: () -> Unit = {}) {
        if (active(e) || installed(e)) return
        if (dir.usableSpace < e.bytes * 2) { status[e.id] = Transfer.Failed("Not enough free space."); return }
        cancelled.remove(e.id)
        status[e.id] = Transfer.Downloading(0, e.bytes)
        Thread {
            val tmp = File(dir, e.file + ".part")
            try {
                if (wifiOnly && isMetered()) throw IllegalStateException("Wi-Fi only is on, and the phone is not on Wi-Fi.")
                val conn = URL(e.url).openConnection() as HttpURLConnection
                conn.connectTimeout = 15_000; conn.readTimeout = 20_000
                if (conn.responseCode != 200) throw IllegalStateException("The server answered ${conn.responseCode}.")
                val digest = MessageDigest.getInstance("SHA-256")
                var done = 0L; var last = 0L
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 18)
                        while (true) {
                            if (e.id in cancelled) throw InterruptedException()
                            val n = input.read(buf); if (n < 0) break
                            digest.update(buf, 0, n); out.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - last > 250) { last = now; status[e.id] = Transfer.Downloading(done, e.bytes) }
                        }
                    }
                }
                status[e.id] = Transfer.Verifying
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex == e.sha256 && tmp.length() == e.bytes) {
                    tmp.renameTo(file(e)); status.remove(e.id)
                    if (selected() == null || selected()?.id == e.id) select(e)
                    onDone()
                } else {
                    tmp.delete(); status[e.id] = Transfer.Failed("The file did not match its checksum and was discarded. Try again.")
                }
            } catch (x: InterruptedException) {
                tmp.delete(); status.remove(e.id)
            } catch (x: Exception) {
                tmp.delete(); status[e.id] = Transfer.Failed(x.message ?: "Download failed. Check the connection and try again.")
            }
        }.start()
    }

    private fun isMetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
