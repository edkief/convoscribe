package com.convoscribe.audio

import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioExporterTest {

    private val target = android.net.Uri.parse("content://documents/team-sync.m4a")

    private lateinit var dir: File
    private lateinit var wav: File
    private var encodes = 0

    @Before
    fun setUp() {
        dir = createTempDirectory("exporter").toFile()
        wav = File(dir, "1.wav").apply { writeBytes(ByteArray(100)) }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun exporter(encode: (File, File, (Float) -> Unit, () -> Unit) -> Unit = ::fakeEncode) =
        AudioExporter(exportDir = File(dir, "exports"), encode = encode)

    private fun fakeEncode(wav: File, out: File, onProgress: (Float) -> Unit, checkCancelled: () -> Unit) {
        encodes++
        checkCancelled()
        onProgress(0.5f)
        out.writeText("m4a")
        onProgress(1f)
    }

    @Test
    fun export_writesTheNamedFileAndReportsProgress() = runBlocking {
        val progress = mutableListOf<Float>()

        val file = exporter().export(1, "Team sync", wav) { progress.add(it) }

        assertEquals("Team sync.m4a", file.name)
        assertEquals("m4a", file.readText())
        assertEquals(listOf(0.5f, 1f), progress)
    }

    @Test
    fun export_reusesAnUpToDateExport() = runBlocking {
        val exporter = exporter()
        val first = exporter.export(1, "Team sync", wav) {}
        first.setLastModified(wav.lastModified() + 1_000)

        val second = exporter.export(1, "Team sync", wav) {}

        assertEquals(first, second)
        assertEquals(1, encodes)
    }

    @Test
    fun export_encodesAgainWhenTheRecordingIsNewer() = runBlocking {
        val exporter = exporter()
        val first = exporter.export(1, "Team sync", wav) {}
        first.setLastModified(wav.lastModified() - 5_000)

        exporter.export(1, "Team sync", wav) {}

        assertEquals(2, encodes)
    }

    @Test
    fun export_leavesNothingBehindWhenEncodingFails() = runBlocking {
        val failing = exporter { _, out, _, _ ->
            out.writeText("half")
            throw IOException("codec died")
        }

        try {
            failing.export(1, "Team sync", wav) {}
            throw AssertionError("expected an IOException")
        } catch (expected: IOException) {
            assertEquals("codec died", expected.message)
        }

        val leftovers = File(dir, "exports").walkTopDown().filter { it.isFile }.toList()
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    @Test
    fun export_cancellationRemovesThePartialFile() = runBlocking {
        val started = java.util.concurrent.CountDownLatch(1)
        val exporter = exporter { _, out, _, checkCancelled ->
            out.writeText("half")
            started.countDown()
            while (true) {
                Thread.sleep(5)
                checkCancelled()
            }
        }

        val job = async(Dispatchers.Default) { exporter.export(1, "Team sync", wav) {} }
        started.await()
        job.cancel()
        try {
            job.await()
            throw AssertionError("expected cancellation")
        } catch (expected: CancellationException) {
            // cancelled, as asked
        }

        val leftovers = File(dir, "exports").walkTopDown().filter { it.isFile }.toList()
        assertTrue(leftovers.toString(), leftovers.isEmpty())
    }

    @Test
    fun saveTo_copiesTheExportToTheChosenLocation() = runBlocking {
        val sink = java.io.ByteArrayOutputStream()
        val exporter = AudioExporter(File(dir, "exports"), ::fakeEncode) { sink }

        exporter.saveTo(target, 1, "Team sync", wav) {}

        assertEquals("m4a", sink.toString())
    }

    @Test
    fun saveTo_failsWhenTheLocationCannotBeOpened() = runBlocking {
        val exporter = AudioExporter(File(dir, "exports"), ::fakeEncode) { null }

        try {
            exporter.saveTo(target, 1, "Team sync", wav) {}
            throw AssertionError("expected an IOException")
        } catch (expected: IOException) {
            // reported to the caller
        }
    }

    @Test
    fun fileName_cleansTitles() {
        assertEquals("Team sync.m4a", AudioExporter.fileName("  Team sync "))
        assertEquals("a_b_c.m4a", AudioExporter.fileName("a/b:c"))
        assertEquals("recording.m4a", AudioExporter.fileName("   "))
        assertEquals("recording.m4a", AudioExporter.fileName("..."))
        assertEquals("Réunion équipe.m4a", AudioExporter.fileName("Réunion équipe"))
        assertEquals(60 + ".m4a".length, AudioExporter.fileName("x".repeat(200)).length)
        assertFalse(AudioExporter.fileName("a\nb").contains('\n'))
    }
}
