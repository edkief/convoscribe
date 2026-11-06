package com.convoscribe.export

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SessionExporterTest {
    /** A folder in memory: files by name, or gone. */
    private class FakeFolder : ExportFolder {
        val files = mutableMapOf<String, Pair<String, ByteArray>>()
        var gone = false
        var failing = false

        override fun remember(treeUri: String, previous: String?): String = "Notes"
        override fun forget(treeUri: String) = Unit
        override fun write(treeUri: String, name: String, mime: String, bytes: ByteArray) {
            if (gone) throw FolderUnavailableException("gone")
            if (failing) throw IOException("disk full")
            files[name] = mime to bytes
        }
    }

    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var cache: File
    private val folder = FakeFolder()
    private lateinit var exporter: SessionExporter

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = SessionRepository(db, File(context.filesDir, "recordings-${System.nanoTime()}").apply { mkdirs() })
        cache = File(context.cacheDir, "exports-${System.nanoTime()}")
        val loader = SessionExportLoader(repo) { ZoneId.of("Europe/Paris") }
        exporter = SessionExporter(loader, folder, cache, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun session(): Long {
        val id = repo.createSession(title = "Réunion budget", language = "fr")
        repo.addSegment(id, startMs = 0L, endMs = 1_000L, text = "Bonjour à tous.", tokens = emptyList())
        return id
    }

    @Test
    fun save_writesTheFileAndReplacesItOnTheNextExport() = runTest {
        val id = session()
        val options = ExportOptions(ExportFormat.MARKDOWN)

        val first = exporter.save(id, options, "content://tree", "Notes") as SessionExporter.Result.Saved
        assertTrue(first.fileName.endsWith(" Réunion budget.md"))
        assertEquals("Notes", first.folderName)
        repo.addSegment(id, startMs = 1_000L, endMs = 2_000L, text = "Deuxième phrase.", tokens = emptyList())
        exporter.save(id, options, "content://tree", "Notes")

        assertEquals(listOf(first.fileName), folder.files.keys.toList())
        val (mime, bytes) = folder.files.getValue(first.fileName)
        assertEquals("application/octet-stream", mime)
        assertTrue(String(bytes).contains("Deuxième phrase."))
    }

    @Test
    fun save_folderGoneOrFailing_isReported() = runTest {
        val id = session()
        folder.gone = true
        assertEquals(
            SessionExporter.Result.FolderUnavailable,
            exporter.save(id, ExportOptions(ExportFormat.PDF), "t", "Notes")
        )
        folder.gone = false
        folder.failing = true
        assertEquals(
            SessionExporter.Result.Failed("disk full"),
            exporter.save(id, ExportOptions(ExportFormat.PDF), "t", "Notes")
        )
        assertEquals(
            SessionExporter.Result.NotFound,
            exporter.save(404L, ExportOptions(ExportFormat.PDF), "t", "Notes")
        )
    }

    @Test
    fun share_plainTextIsTextAndOtherFormatsAFile() = runTest {
        val id = session()
        val text = exporter.share(id, ExportOptions(ExportFormat.TEXT)) as SessionExporter.Result.Shared
        assertNull(text.file)
        assertEquals("text/plain", text.mime)
        assertTrue(text.text!!.contains("[00:00] Bonjour à tous."))

        val pdf = exporter.share(id, ExportOptions(ExportFormat.PDF)) as SessionExporter.Result.Shared
        assertEquals("application/pdf", pdf.mime)
        assertEquals(File(cache, id.toString()), pdf.file!!.parentFile)
        assertTrue(pdf.file!!.readBytes().decodeToString().startsWith("%PDF-1.4"))

        // A new share replaces the previous file for this session.
        val srt = exporter.share(id, ExportOptions(ExportFormat.SRT)) as SessionExporter.Result.Shared
        assertTrue(srt.file!!.isFile)
        assertFalse(pdf.file!!.exists())
        assertEquals(1, File(cache, id.toString()).list()!!.size)
    }
}
