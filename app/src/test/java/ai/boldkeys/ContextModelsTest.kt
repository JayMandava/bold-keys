package ai.boldkeys

import android.content.Context
import android.net.Uri
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** No real model weights or network requests: tiny synthetic payloads and sparse file-size fixtures. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33, 36])
class ContextModelsTest {
    private lateinit var context: Context
    private val payload = "synthetic model fixture".toByteArray()
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fixture() = ModelEntry("fixture", "Fixture", "Test only", "Test", "fixture.gguf", "https://invalid.example/fixture", digest(payload), payload.size.toLong())

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("bk_models", Context.MODE_PRIVATE).edit().clear().commit()
        ContextModels.init(context)
        ContextModels.dir.listFiles()?.forEach { it.deleteRecursively() }
        ContextModels.status.clear()
    }

    private class Connection(private val response: ByteArray, private val code: Int = 200,
        private val source: InputStream = ByteArrayInputStream(response), private val cleanup: () -> Unit = {}) : HttpURLConnection(URL("https://invalid.example/fixture")) {
        var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true; cleanup() }
        override fun usingProxy() = false
        override fun getResponseCode() = code
        override fun getInputStream() = source
    }

    private fun sparse(entry: ModelEntry) { RandomAccessFile(ContextModels.file(entry), "rw").use { it.setLength(entry.bytes) } }
    private fun prefs() = context.getSharedPreferences("bk_models", Context.MODE_PRIVATE)

    @Test fun catalogueAppendsPinnedQatWithoutChangingExistingIdsOrOrder() {
        val list = ContextModels.catalogue
        assertEquals(listOf("gemma3-270m-q4", "gemma3-270m-q8", "gemma3-270m-qat-q4"), list.map { it.id })
        assertEquals(3, list.map { it.file }.distinct().size)
        val qat = list[2]
        assertEquals(241_410_208L, qat.bytes)
        assertEquals("5f4b2e17722e510122c464573b880587f4983347a40e5472b858d5a3c1ab8095", qat.sha256)
        assertTrue(qat.url.contains("/resolve/a909196bf1a24eb8cb855e9b7b73742e0b732fc6/"))
        assertEquals("Gemma terms", qat.license)
        assertTrue(qat.note.contains("Optional"))
    }

    @Test fun allThreeModelsCanBeSelectedIndependentlyAndDefaultOrderIsUnchanged() {
        assertNull(ContextModels.selected())
        assertFalse(Prefs.contextModel(context))
        ContextModels.catalogue.forEach(::sparse)
        assertEquals("gemma3-270m-q4", ContextModels.selected()?.id)
        for (e in ContextModels.catalogue) {
            ContextModels.select(e); ContextModels.init(context)
            assertEquals(e.id, ContextModels.selected()?.id)
            assertEquals(e.id, prefs().getString("selected", null))
        }
    }

    @Test fun missingChoiceFallsBackWithoutOverwritingItsSavedIdAndReturnsWhenRestored() {
        val (q4, q8, qat) = ContextModels.catalogue
        listOf(q4,q8,qat).forEach(::sparse)
        ContextModels.select(q8)
        ContextModels.file(q8).delete()
        assertEquals(q4.id, ContextModels.selected()?.id)
        assertEquals(q8.id, prefs().getString("selected", null))
        sparse(q8); assertEquals(q8.id, ContextModels.selected()?.id)
        ContextModels.select(qat); ContextModels.delete(qat)
        assertEquals(q4.id, ContextModels.selected()?.id)
        assertTrue(ContextModels.installed(q8))
    }

    @Test fun legacyImportedQatChoiceIsPreservedAndCanonicalChoiceRemainsSelectable() {
        val qat = ContextModels.catalogue[2]
        sparse(qat)
        val legacyId = "custom-${qat.file}"
        prefs().edit().putString("custom", "${qat.file}\tExisting imported QAT").putString("selected", legacyId).commit()
        assertEquals(legacyId, ContextModels.selected()?.id)
        ContextModels.select(qat); assertEquals(qat.id, ContextModels.selected()?.id)
        assertTrue(ContextModels.entries().any { it.id == legacyId })
    }

    @Test fun successfulMockDownloadVerifiesBytesCleansConnectionAndPreservesExplicitChoice() {
        val old = ContextModels.catalogue.first()
        ContextModels.select(old) // temporarily missing explicit choice must survive a different download
        val e = fixture(); val connection = Connection(payload); var completed = false
        ContextModels.downloadFile(e, false, { completed = true }, { url -> assertEquals(e.url, url); connection })
        assertTrue(completed); assertTrue(connection.disconnected)
        assertArrayEquals(payload, ContextModels.file(e).readBytes())
        assertNull(ContextModels.status[e.id])
        assertEquals(old.id, prefs().getString("selected", null))
        assertFalse(ContextModels.dir.listFiles()!!.any { it.name.endsWith(".part") })
    }

    @Test fun failedDownloadsNeverReplaceFilesSelectModelsOrCallCompletion() {
        val e = fixture()
        val old = ContextModels.catalogue.first(); sparse(old); ContextModels.select(old)
        for ((bytes, code) in listOf(payload to 403, "wrong".toByteArray() to 200, payload.dropLast(1).toByteArray() to 200,
            (payload + byteArrayOf(0)) to 200, payload.reversedArray() to 200)) {
            ContextModels.file(e).writeText("keep previous file")
            var completed = false
            val conn = Connection(bytes, code)
            ContextModels.downloadFile(e, false, { completed = true }, { conn })
            assertFalse(completed); assertTrue(conn.disconnected)
            assertTrue(ContextModels.status[e.id] is Transfer.Failed)
            assertEquals("keep previous file", ContextModels.file(e).readText())
            assertEquals(old.id, ContextModels.selected()?.id)
            assertFalse(ContextModels.dir.listFiles()!!.any { it.name.endsWith(".part") })
        }
    }

    @Test fun meteredPolicyAndNetworkErrorsAreSafeAndCancellationDoesNotInstall() {
        val e = fixture(); var opened = false
        ContextModels.downloadFile(e, true, open = { opened = true; Connection(payload) }, metered = { true })
        assertFalse(opened); assertTrue(ContextModels.status[e.id] is Transfer.Failed)
        ContextModels.downloadFile(e, false, open = { throw IOException("Synthetic offline") })
        assertTrue(ContextModels.status[e.id] is Transfer.Failed)
        ContextModels.cancel(e)
        val conn = Connection(payload)
        ContextModels.downloadFile(e, false, open = { conn })
        assertNull(ContextModels.status[e.id]); assertFalse(ContextModels.file(e).exists()); assertTrue(conn.disconnected)
    }

    @Test fun installationFailureCannotLookLikeSuccessOrReplaceTheSelection() {
        val e = fixture(); val target = ContextModels.file(e)
        target.mkdir(); target.resolve("preserve").writeText("existing directory")
        var done = false
        ContextModels.downloadFile(e, false, { done = true }, { Connection(payload) })
        assertFalse(done); assertTrue(ContextModels.status[e.id] is Transfer.Failed)
        assertTrue(target.resolve("preserve").exists())
        assertNull(prefs().getString("selected", null))
    }

    @Test fun publicImportRecognizesCatalogueFilenamesAndKeepsCustomImportsWorking() {
        val uri = Uri.parse("content://synthetic/import")
        val qat = ContextModels.catalogue[2]
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))
        try { ContextModels.import(uri, qat.file); fail("wrong catalogue bytes accepted") } catch (_: IllegalStateException) { }
        assertFalse(ContextModels.file(qat).exists())
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))
        val custom = ContextModels.import(uri, "my model.gguf")
        assertEquals("custom-my_model.gguf", custom.id)
        assertEquals(custom.id, ContextModels.selected()?.id)
        ContextModels.init(context)
        assertEquals(custom.id, ContextModels.selected()?.id)
        assertArrayEquals(payload, ContextModels.file(custom).readBytes())
    }

    @Test fun legacyAliasDeletionRemovesItsSharedFileButKeepsOtherModels() {
        val qat = ContextModels.catalogue[2]
        ContextModels.catalogue.forEach(::sparse)
        prefs().edit().putString("custom", "${qat.file}\tLegacy QAT").commit()
        val alias = ContextModels.entries().first { it.custom }
        ContextModels.select(alias); ContextModels.delete(alias)
        assertFalse(ContextModels.installed(qat))
        assertTrue(ContextModels.catalogue.take(2).all(ContextModels::installed))
        assertEquals(ContextModels.catalogue[0].id, ContextModels.selected()?.id)
    }

    @Test fun cleanupFinishesBeforeRetryIsExposed() {
        val e = fixture()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val conn = Connection(payload, cleanup = { entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS)) })
        ContextModels.status[e.id] = Transfer.Downloading(0, e.bytes)
        val worker = Thread { ContextModels.downloadFile(e, false, open = { conn }) }.apply { start() }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(ContextModels.active(e))
        } finally { release.countDown(); worker.join(5000) }
        assertFalse(worker.isAlive)
        assertFalse(ContextModels.active(e))
        assertNull(ContextModels.status[e.id])
    }

    @Test fun catalogueImportRequiresChecksumAndDoesNotClobberOnFailure() {
        val e = fixture(); val uri = Uri.parse("content://synthetic/model")
        ContextModels.file(e).writeText("existing")
        prefs().edit().putString("selected", "existing-choice").commit()
        for (bad in listOf(payload.reversedArray(), payload.dropLast(1).toByteArray())) {
            shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bad))
            try { ContextModels.importFile(uri, e.file, "fixture.gguf", e); fail("bad bytes accepted") } catch (_: IllegalStateException) { }
            assertEquals("existing", ContextModels.file(e).readText())
            assertEquals("existing-choice", prefs().getString("selected", null))
        }
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(payload))
        assertSame(e, ContextModels.importFile(uri, e.file, "fixture.gguf", e))
        assertArrayEquals(payload, ContextModels.file(e).readBytes())
        assertEquals(e.id, prefs().getString("selected", null))
        assertTrue(ContextModels.dir.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
