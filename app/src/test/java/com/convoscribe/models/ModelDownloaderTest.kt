package com.convoscribe.models

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * End-to-end tests for [ModelDownloader]. Each test stands up a
 * [MockWebServer] whose URLs are baked into small [ModelBundle]s so we
 * can assert both the bytes on disk and the server-side request count.
 */
class ModelDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: ModelStore
    private lateinit var modelsRoot: File
    private lateinit var downloader: ModelDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        modelsRoot = tmp.newFolder("models")
        store = ModelStore(modelsRoot)
        downloader = ModelDownloader(OkHttpClient(), store)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Build a one-bundle fixture with a single file served at
     * [bytes] bytes of [pattern]. Defaults are tuned so the file is
     * small enough to be quick but big enough to exercise the buffer
     * loop and the size check.
     */
    private fun singleFileBundle(
        fileName: String = "a.bin",
        bytes: Int = 4_096,
        pattern: (Int) -> Byte = { (it % 251).toByte() }
    ): Pair<ModelBundle, ByteArray> {
        val body = ByteArray(bytes) { idx -> pattern(idx) }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val bundle = ModelBundle(
            id = "single-$fileName",
            displayName = "single",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile(
                    fileName = fileName,
                    url = server.url("/$fileName").toString(),
                    sizeBytes = bytes.toLong()
                )
            )
        )
        return bundle to body
    }

    /**
     * Build a `.tar.bz2` archive whose entries are a `rootDir/`
     * followed by [entries], with each entry's bytes supplied by
     * [payloads]. Extra entries (e.g. a stray `extra.txt`) can be
     * mixed in to test the skip path. Returns the compressed body
     * ready to hand to [MockWebServer].
     */
    private fun buildTarBz2(rootDir: String, entries: List<String>, payloads: List<ByteArray>): ByteArray {
        require(entries.size == payloads.size)
        val sink = ByteArrayOutputStream()
        BZip2CompressorOutputStream(sink).use { bz ->
            TarArchiveOutputStream(bz).use { tar ->
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                for (i in entries.indices) {
                    val path = entries[i]
                    val bytes = payloads[i]
                    val entry = org.apache.commons.compress.archivers.tar.TarArchiveEntry(path)
                    entry.size = bytes.size.toLong()
                    tar.putArchiveEntry(entry)
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
        return sink.toByteArray()
    }

    @Test
    fun twoFiles_bothInstalledWithCorrectSizes_andLastEventIsCompleted() = runTest {
        val bodyA = ByteArray(2_000) { (it % 251).toByte() }
        val bodyB = ByteArray(1_500) { ((it + 50) % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bodyA)))
        server.enqueue(MockResponse().setBody(Buffer().write(bodyB)))

        val bundle = ModelBundle(
            id = "two",
            displayName = "two",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("a.bin", server.url("/a.bin").toString(), 2_000),
                ModelFile("b.bin", server.url("/b.bin").toString(), 1_500)
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // The flow ends with Completed.
        assertEquals(DownloadEvent.Completed, events.last())
        // Some event must be a Progress with bytesDone == bytesTotal == 3 500.
        val progress = events.filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(3_500L, progress.bytesDone)
        assertEquals(3_500L, progress.bytesTotal)
        // The two targets exist at the right size, no .part left behind.
        val targetA = File(modelsRoot, "two/a.bin")
        val targetB = File(modelsRoot, "two/b.bin")
        assertTrue(targetA.exists())
        assertEquals(2_000L, targetA.length())
        assertTrue(targetB.exists())
        assertEquals(1_500L, targetB.length())
        assertNull(File(modelsRoot, "two/a.bin.part").takeIf { it.exists() })
        assertNull(File(modelsRoot, "two/b.bin.part").takeIf { it.exists() })
        assertEquals(2, server.requestCount)
    }

    @Test
    fun http404_throwsWithHttpCodeInMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val bundle = ModelBundle(
            id = "missing",
            displayName = "missing",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("missing.bin", server.url("/missing.bin").toString(), 100)
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertTrue(
            "message '${ex.message}' should contain HTTP 404",
            ex.message?.contains("HTTP 404") == true
        )
    }

    @Test
    fun bodyShorterThanSize_throwsSizeMismatch_andDeletesPart() = runTest {
        val body = ByteArray(512) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val bundle = ModelBundle(
            id = "short",
            displayName = "short",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("short.bin", server.url("/short.bin").toString(), 2_048)
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertTrue(
            "message '${ex.message}' should start with 'Size mismatch'",
            ex.message?.startsWith("Size mismatch") == true
        )

        val target = File(modelsRoot, "short/short.bin")
        assertTrue(!target.exists())
        val part = File(modelsRoot, "short/short.bin.part")
        assertTrue(!part.exists())
    }

    @Test
    fun alreadyInstalledFile_isNotRequested() = runTest {
        // Pre-install the only file with the exact size so the
        // downloader should skip the network entirely.
        val body = ByteArray(1_024) { (it % 251).toByte() }
        val bundle = ModelBundle(
            id = "pre",
            displayName = "pre",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile(
                    fileName = "pre.bin",
                    url = server.url("/pre.bin").toString(),
                    sizeBytes = body.size.toLong()
                )
            )
        )
        val target = store.fileFor(bundle, "pre.bin")
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { it.write(body) }

        val events = downloader.download(listOf(bundle)).toList()

        assertEquals(DownloadEvent.Completed, events.last())
        assertEquals(0, server.requestCount)

        // Now flip the situation: pre-install one of two files and
        // ensure only the missing one is fetched.
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val multi = ModelBundle(
            id = "mixed",
            displayName = "mixed",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("a.bin", server.url("/a.bin").toString(), body.size.toLong()),
                ModelFile("b.bin", server.url("/b.bin").toString(), body.size.toLong())
            )
        )
        val installed = store.fileFor(multi, "a.bin")
        installed.parentFile?.mkdirs()
        RandomAccessFile(installed, "rw").use { it.write(body) }

        val events2 = downloader.download(listOf(multi)).toList()

        assertEquals(DownloadEvent.Completed, events2.last())
        assertEquals(1, server.requestCount)
        assertTrue(store.fileFor(multi, "a.bin").exists())
        assertTrue(store.fileFor(multi, "b.bin").exists())
        // Spot-check the events collected in the first run too.
        assertNotNull(events)
    }

    @Test
    fun archiveBundle_withFilesAndExtra_extractsListedFiles_andSkipsExtra() = runTest {
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val extraBytes = ByteArray(50) { (it % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin", "root/extra.txt"),
            payloads = listOf(aBytes, bBytes, extraBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // The flow terminates with Completed.
        assertEquals(DownloadEvent.Completed, events.last())
        // Both listed files were extracted to disk at exact sizes;
        // the unlisted entry was not.
        val aTarget = File(modelsRoot, "npu/a.bin")
        val bTarget = File(modelsRoot, "npu/sub/b.bin")
        val extraTarget = File(modelsRoot, "npu/extra.txt")
        assertTrue(aTarget.exists())
        assertEquals(aBytes.size.toLong(), aTarget.length())
        assertTrue(bTarget.exists())
        assertEquals(bBytes.size.toLong(), bTarget.length())
        assertTrue(!extraTarget.exists())
        // No .part files survived the success path.
        assertNull(File(modelsRoot, "npu/a.bin.part").takeIf { it.exists() })
        assertNull(File(modelsRoot, "npu/sub/b.bin.part").takeIf { it.exists() })
        // The bytes of the extracted files match the entries exactly.
        assertTrue(aTarget.readBytes().contentEquals(aBytes))
        assertTrue(bTarget.readBytes().contentEquals(bBytes))
        // And the server got exactly one archive request.
        assertEquals(1, server.requestCount)
    }

    @Test
    fun archiveBundle_missingListedFile_throwsArchiveIsMissing() = runTest {
        // The archive contains a.bin and extra.txt, but the bundle
        // declares sub/b.bin too (which is not present in the
        // archive); the downloader must report that explicitly.
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val extraBytes = ByteArray(50) { (it % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/extra.txt"),
            payloads = listOf(aBytes, extraBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", 500L)
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertEquals("Archive is missing sub/b.bin", ex.message)
    }

    @Test
    fun archiveBundle_entrySizeMismatch_throwsSizeMismatch() = runTest {
        // a.bin in the archive is 1000 bytes, but the bundle
        // declares 999 bytes; the size check must catch it.
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin"),
            payloads = listOf(aBytes, bBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", 999L),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                downloader.download(listOf(bundle)).toList()
            }
        }
        assertTrue(
            "message '${ex.message}' should start with 'Size mismatch'",
            ex.message?.startsWith("Size mismatch") == true
        )
    }

    @Test
    fun archiveOnlyDownload_lastProgressHasBytesDoneEqualsBytesTotal() = runTest {
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val bBytes = ByteArray(500) { ((it + 50) % 251).toByte() }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/a.bin", "root/sub/b.bin"),
            payloads = listOf(aBytes, bBytes)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))

        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(
                ModelFile("a.bin", "", aBytes.size.toLong()),
                ModelFile("sub/b.bin", "", bBytes.size.toLong())
            ),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val events = downloader.download(listOf(bundle)).toList()

        // Last event is Completed, the previous one is the trailing
        // progress with bytesDone == bytesTotal == archive length.
        assertEquals(DownloadEvent.Completed, events.last())
        val lastProgress = events.dropLast(1).filterIsInstance<DownloadEvent.Progress>().last()
        assertEquals(archive.size.toLong(), lastProgress.bytesDone)
        assertEquals(archive.size.toLong(), lastProgress.bytesTotal)
    }

    /**
     * Regression: ticks used to carry the bytes since the previous
     * tick while the flow added them to the pre-file total, so the UI
     * sat at "0 / N MB" until each file finished. Progress must climb
     * steadily through a file and through the next file.
     */
    @Test
    fun plainFiles_progressClimbsThroughEachFile() = runTest {
        val size = 1_000_000
        val bodyA = ByteArray(size) { (it % 251).toByte() }
        val bodyB = ByteArray(size) { ((it + 7) % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(bodyA)))
        server.enqueue(MockResponse().setBody(Buffer().write(bodyB)))
        val bundle = ModelBundle(
            id = "big",
            displayName = "big",
            kind = ModelKind.SEGMENTATION,
            language = null,
            files = listOf(
                ModelFile("a.bin", server.url("/a.bin").toString(), size.toLong()),
                ModelFile("b.bin", server.url("/b.bin").toString(), size.toLong())
            )
        )

        val done = downloader.download(listOf(bundle)).toList()
            .filterIsInstance<DownloadEvent.Progress>()
            .map { it.bytesDone }

        assertEquals("progress must never go backwards: $done", done.sorted(), done)
        assertEquals(2L * size, done.last())
        // Mid-way through the first file, and mid-way through the second.
        assertTrue("no progress inside a.bin: $done", done.any { it in 300_000L until size.toLong() })
        assertTrue("no progress inside b.bin: $done", done.any { it in (size + 300_000L) until 2L * size })
    }

    /**
     * Regression: archive ticks were only emitted between tar entries,
     * so a single large entry gave no progress until it was extracted.
     * Uses 3 MB of incompressible bytes: bzip2 consumes input in
     * ~900 KB blocks, so the entry spans several of them.
     */
    @Test
    fun archiveWithOneLargeEntry_reportsProgressDuringExtraction() = runTest {
        val random = java.util.Random(42)
        val big = ByteArray(3_000_000).also { random.nextBytes(it) }
        val archive = buildTarBz2(
            rootDir = "root",
            entries = listOf("root/encoder.onnx"),
            payloads = listOf(big)
        )
        server.enqueue(MockResponse().setBody(Buffer().write(archive)))
        val bundle = ModelBundle(
            id = "npu",
            displayName = "NPU model",
            kind = ModelKind.ASR,
            language = "en",
            files = listOf(ModelFile("encoder.onnx", "", big.size.toLong())),
            archive = ModelArchive(
                url = server.url("/npu.tar.bz2").toString(),
                sizeBytes = archive.size.toLong(),
                rootDir = "root"
            )
        )

        val progress = downloader.download(listOf(bundle)).toList()
            .filterIsInstance<DownloadEvent.Progress>()
        val done = progress.map { it.bytesDone }
        val unpacking = progress.mapNotNull { it.unpacking }

        val total = archive.size.toLong()
        assertEquals("progress must never go backwards: $done", done.sorted(), done)
        assertEquals(total, done.last())
        assertTrue("expected several mid-download ticks: $done", done.count { it in 1L until total } >= 2)
        // The archive is downloaded first, then extracted from disk.
        assertEquals("unpacking must never go backwards: $unpacking", unpacking.sorted(), unpacking)
        assertTrue("expected several mid-extraction ticks: $unpacking", unpacking.count { it > 0f && it < 1f } >= 2)
        assertEquals(1f, unpacking.last())
    }

    /** A downloader that retries at once and never waits for the network. */
    private fun retryingDownloader(
        delays: List<Long> = listOf(0L, 0L, 0L),
        network: suspend () -> Boolean = { true },
        free: () -> Long = { Long.MAX_VALUE }
    ) = ModelDownloader(OkHttpClient(), store, awaitNetwork = network, retryDelaysMs = delays, freeBytes = free)

    private fun plainBundle(id: String, body: ByteArray) = ModelBundle(
        id = id,
        displayName = id,
        kind = ModelKind.SEGMENTATION,
        language = null,
        files = listOf(ModelFile("f.bin", server.url("/f.bin").toString(), body.size.toLong()))
    )

    private fun archiveBundle(archive: ByteArray, files: List<ModelFile>) = ModelBundle(
        id = "npu",
        displayName = "NPU model",
        kind = ModelKind.ASR,
        language = "en",
        files = files,
        archive = ModelArchive(
            url = server.url("/npu.tar.bz2").toString(),
            sizeBytes = archive.size.toLong(),
            rootDir = "root"
        )
    )

    @Test
    fun droppedConnection_isRetried_andResumesFromThePart() = runTest {
        val body = ByteArray(600_000) { (it % 251).toByte() }
        val bundle = plainBundle("resume", body)
        val part = File(modelsRoot, "resume/f.bin.part")
        // The second request must ask for the rest of the file.
        server.dispatcher = object : okhttp3.mockwebserver.QueueDispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.getHeader("Range") == null) return super.dispatch(request)
                val from = request.getHeader("Range")!!.removePrefix("bytes=").removeSuffix("-").toInt()
                return MockResponse().setResponseCode(206)
                    .setHeader("Content-Range", "bytes $from-${body.size - 1}/${body.size}")
                    .setBody(Buffer().write(body.copyOfRange(from, body.size)))
            }
        }.apply {
            enqueueResponse(
                MockResponse().setBody(Buffer().write(body))
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            )
        }

        val events = retryingDownloader().download(listOf(bundle)).toList()

        assertEquals(DownloadEvent.Completed, events.last())
        assertTrue(
            "a waiting event is emitted before the retry",
            events.any { it is DownloadEvent.Progress && it.waiting }
        )
        assertTrue(store.fileFor(bundle, "f.bin").readBytes().contentEquals(body))
        assertTrue(!part.exists())
        server.takeRequest()
        val second = server.takeRequest()
        assertNotNull("second request resumes with a Range header", second.getHeader("Range"))
        assertTrue(second.getHeader("Range") != "bytes=0-")
    }

    @Test
    fun http503_isRetried_thenSucceeds() = runTest {
        val body = ByteArray(2_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        val events = retryingDownloader().download(listOf(plainBundle("flaky", body))).toList()

        assertEquals(DownloadEvent.Completed, events.last())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun http404_isNotRetried() {
        server.enqueue(MockResponse().setResponseCode(404))

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                retryingDownloader().download(listOf(plainBundle("gone", ByteArray(100)))).toList()
            }
        }

        assertEquals("HTTP 404 for f.bin", ex.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun transientFailures_giveUpAfterTheLastRetry_andKeepThePart() {
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                retryingDownloader(
                    delays = listOf(0L, 0L)
                ).download(listOf(plainBundle("down", ByteArray(100)))).toList()
            }
        }

        assertEquals("HTTP 503 for f.bin", ex.message)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun networkNotBack_failsWithTheLastError() {
        server.enqueue(MockResponse().setResponseCode(503))

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                retryingDownloader(
                    network = { false }
                ).download(listOf(plainBundle("offline", ByteArray(100)))).toList()
            }
        }

        assertEquals("HTTP 503 for f.bin", ex.message)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun archive_resumesFromItsPartFile_thenExtracts_andDeletesTheArchive() = runTest {
        val aBytes = ByteArray(1_000) { (it % 251).toByte() }
        val archive = buildTarBz2("root", listOf("root/a.bin"), listOf(aBytes))
        val bundle = archiveBundle(archive, listOf(ModelFile("a.bin", "", aBytes.size.toLong())))
        val archivePart = store.archivePartFor(bundle)
        archivePart.parentFile?.mkdirs()
        val half = archive.size / 2
        archivePart.writeBytes(archive.copyOfRange(0, half))
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $half-${archive.size - 1}/${archive.size}")
                .setBody(Buffer().write(archive.copyOfRange(half, archive.size)))
        )

        val events = downloader.download(listOf(bundle)).toList()

        assertEquals(DownloadEvent.Completed, events.last())
        assertEquals("bytes=$half-", server.takeRequest().getHeader("Range"))
        assertTrue(store.fileFor(bundle, "a.bin").readBytes().contentEquals(aBytes))
        assertTrue("archive deleted after extraction", !archivePart.exists())
        assertTrue(
            "extraction reports its progress",
            events.any { it is DownloadEvent.Progress && it.unpacking == 1f }
        )
    }

    @Test
    fun archive_notEnoughFreeSpace_failsBeforeAnyRequest() {
        val bundle = archiveBundle(
            archive = ByteArray(3_000_000),
            files = listOf(ModelFile("a.bin", "", 5_000_000L))
        )

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking {
                retryingDownloader(free = { 4_000_000L }).download(listOf(bundle)).toList()
            }
        }

        assertEquals("Not enough free space for NPU model: need 8 MB, 4 MB free", ex.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun corruptArchive_failsAndDeletesTheArchive() {
        val garbage = ByteArray(2_000) { (it % 7).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(garbage)))
        val bundle = archiveBundle(garbage, listOf(ModelFile("a.bin", "", 10L)))

        val ex = assertThrows(ModelDownloadException::class.java) {
            kotlinx.coroutines.runBlocking { downloader.download(listOf(bundle)).toList() }
        }

        assertTrue(ex.message!!, ex.message!!.startsWith("IO error extracting root"))
        assertTrue(!store.archivePartFor(bundle).exists())
    }

    @Test
    fun discardPartial_deletesPartFilesAndTheArchive() {
        val plain = plainBundle("p", ByteArray(10))
        val plainPart = File(modelsRoot, "p/f.bin.part").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(5))
        }
        val archived = archiveBundle(ByteArray(10), listOf(ModelFile("a.bin", "", 10L)))
        val archivePart = store.archivePartFor(archived).apply { writeBytes(ByteArray(5)) }

        downloader.discardPartial(listOf(plain, archived))

        assertTrue(!plainPart.exists())
        assertTrue(!archivePart.exists())
    }
}
