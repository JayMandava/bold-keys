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
        ModelEntry(
            "gemma3-270m-qat-q4", "Gemma 3 270M (QAT)", "Optional quantization-aware model. Google weights converted by the LM Studio team; no comparative quality or speed claim.", GEMMA,
            "gemma-3-270m-it-qat-Q4_0.gguf", "$HF/lmstudio-community/gemma-3-270m-it-qat-GGUF/resolve/a909196bf1a24eb8cb855e9b7b73742e0b732fc6/gemma-3-270m-it-qat-Q4_0.gguf",
            "5f4b2e17722e510122c464573b880587f4983347a40e5472b858d5a3c1ab8095", 241_410_208,
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

    /** Copies a .gguf locally; catalogue filenames must match their pinned identity before replacement. */
    fun import(uri: Uri, displayName: String): ModelEntry {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return importFile(uri, safe, displayName, catalogue.firstOrNull { it.file == safe })
    }

    internal fun importFile(uri: Uri, safe: String, displayName: String, known: ModelEntry?): ModelEntry {
        val tmp = File.createTempFile("import-", ".tmp", dir)
        try {
            app.contentResolver.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
            if (known != null) check(tmp.length() == known.bytes && checksum(tmp) == known.sha256) {
                "The file does not match the catalogue model's size and checksum."
            }
            check(tmp.renameTo(File(dir, safe))) { "Could not install the imported model." }
            val entry = known ?: ModelEntry("custom-$safe", displayName.substringBeforeLast('.'), "Imported from a file.", "Your own", safe, "", "", 0, custom = true)
            if (known == null) saveCustoms(customs().filter { it.id != entry.id } + entry)
            select(entry)
            return entry
        } finally { tmp.delete() }
    }

    private fun checksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 18)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ---- downloading

    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun active(e: ModelEntry) = status[e.id].let { it is Transfer.Downloading || it == Transfer.Verifying }
    fun cancel(e: ModelEntry) { cancelled.add(e.id) }

    /** Fetches only after the user requests a download. */
    fun download(e: ModelEntry, wifiOnly: Boolean, onDone: () -> Unit = {}) {
        if (active(e) || installed(e)) return
        if (dir.usableSpace < e.bytes * 2) { status[e.id] = Transfer.Failed("Not enough free space."); return }
        cancelled.remove(e.id)
        status[e.id] = Transfer.Downloading(0, e.bytes)
        Thread { downloadFile(e, wifiOnly, onDone) }.start()
    }

    /** Synchronous transfer core; tests supply synthetic connections, never model endpoints. */
    internal fun downloadFile(e: ModelEntry, wifiOnly: Boolean, onDone: () -> Unit = {},
        open: (String) -> HttpURLConnection = { URL(it).openConnection() as HttpURLConnection },
        metered: () -> Boolean = { isMetered() }) {
        var tmp: File? = null
        var conn: HttpURLConnection? = null
        var failure: String? = null
        var completed = false
        try {
            val output = File.createTempFile("download-", ".part", dir)
            tmp = output
            if (wifiOnly && metered()) throw IllegalStateException("Wi-Fi only is on, and the phone is not on Wi-Fi.")
            conn = open(e.url)
            conn.connectTimeout = 15_000; conn.readTimeout = 20_000
            if (conn.responseCode != 200) throw IllegalStateException("The server answered ${conn.responseCode}.")
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L; var last = 0L
            conn.inputStream.use { input ->
                output.outputStream().use { out ->
                    val buf = ByteArray(1 shl 18)
                    while (true) {
                        if (e.id in cancelled) throw InterruptedException()
                        val n = input.read(buf); if (n < 0) break
                        if (n > e.bytes - done) throw IllegalStateException("The download exceeded the expected model size and was discarded.")
                        digest.update(buf, 0, n); out.write(buf, 0, n); done += n
                        val now = System.currentTimeMillis()
                        if (now - last > 250) { last = now; status[e.id] = Transfer.Downloading(done, e.bytes) }
                    }
                }
            }
            if (e.id in cancelled) throw InterruptedException()
            status[e.id] = Transfer.Verifying
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            check(hex == e.sha256 && output.length() == e.bytes) { "The file did not match its checksum and was discarded. Try again." }
            check(output.renameTo(file(e))) { "Could not install the verified model." }
            // Preserve an explicit saved choice even when its file is temporarily missing.
            if (sp.getString("selected", null) == null) select(e)
            completed = true
        } catch (_: InterruptedException) {
            // Cancellation completes after transfer cleanup below.
        } catch (x: Exception) {
            failure = x.message ?: "Download failed. Check the connection and try again."
        } finally {
            tmp?.delete()
            runCatching { conn?.disconnect() }
            cancelled.remove(e.id)
            // Only expose retry/completion after the prior transfer has released every resource.
            if (failure != null) status[e.id] = Transfer.Failed(failure) else status.remove(e.id)
        }
        if (completed) onDone()
    }

    private fun isMetered(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        return cm.isActiveNetworkMetered
    }
}
