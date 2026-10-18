package com.convoscribe.update

import com.convoscribe.models.ModelDownloadException
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [UpdateDownloader] against a [MockWebServer]: sha256 check, resume, retries and clean-up. */
class UpdateDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private lateinit var downloader: UpdateDownloader
    private val apkBytes = ByteArray(300_000) { (it % 251).toByte() }
    private val sha = MessageDigest.getInstance("SHA-256").digest(apkBytes).joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        dir = tmp.newFolder("updates")
        downloader = UpdateDownloader(OkHttpClient(), dir, retryDelaysMs = listOf(1L, 1L))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun offer(withSha: Boolean = true, size: Long = apkBytes.size.toLong()) = UpdateOffer(
        version = SemVer(0, 3, 0),
        release = Release("v0.3.0", "0.3.0", "", "", prerelease = false, draft = false, assets = emptyList()),
        apk = ReleaseAsset("convoscribe-0.3.0.apk", server.url("/convoscribe-0.3.0.apk").toString(), size),
        sha256 = if (withSha) {
            ReleaseAsset("convoscribe-0.3.0.apk.sha256", server.url("/convoscribe-0.3.0.apk.sha256").toString(), 88)
        } else {
            null
        }
    )

    /** Serves the APK (honouring Range) and a sha256 file holding [shaText]. */
    private fun serve(shaText: String = "$sha  convoscribe-0.3.0.apk\n") {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/convoscribe-0.3.0.apk.sha256" -> MockResponse().setBody(shaText)
                "/convoscribe-0.3.0.apk" -> {
                    val range = request.getHeader("Range")
                    if (range == null) {
                        MockResponse().setBody(Buffer().write(apkBytes))
                    } else {
                        val from = range.removePrefix("bytes=").removeSuffix("-").toInt()
                        MockResponse().setResponseCode(206)
                            .setHeader("Content-Range", "bytes $from-${apkBytes.size - 1}/${apkBytes.size}")
                            .setBody(Buffer().write(apkBytes.copyOfRange(from, apkBytes.size)))
                    }
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun download(offer: UpdateOffer = offer(), progress: MutableList<Long> = mutableListOf()) =
        runBlocking { downloader.download(offer) { done, _, _ -> progress += done } }

    @Test
    fun downloadsVerifiesAndRenames() {
        serve()
        val progress = mutableListOf<Long>()
        val apk = download(progress = progress)
        assertEquals(File(dir, "0.3.0/convoscribe-0.3.0.apk"), apk)
        assertArrayEquals(apkBytes, apk.readBytes())
        assertFalse(File(apk.path + ".part").exists())
        assertEquals(apkBytes.size.toLong(), progress.last())
    }

    @Test
    fun sha256Mismatch_deletesTheApk() {
        serve(shaText = "${"0".repeat(64)}  convoscribe-0.3.0.apk\n")
        assertThrows(Sha256MismatchException::class.java) { download() }
        assertFalse(downloader.apkFile(offer()).exists())
    }

    @Test
    fun withoutSha256Asset_theDownloadIsKeptUnchecked() {
        serve()
        val apk = download(offer(withSha = false))
        assertTrue(apk.exists())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun garbageSha256File_failsTheDownload() {
        serve(shaText = "not a digest")
        assertThrows(ModelDownloadException::class.java) { download() }
    }

    @Test
    fun aPartFromAnEarlierRun_isResumedWithRange() {
        serve()
        val part = File(dir, "0.3.0/convoscribe-0.3.0.apk.part")
        part.parentFile?.mkdirs()
        part.writeBytes(apkBytes.copyOfRange(0, 100_000))
        val apk = download()
        assertArrayEquals(apkBytes, apk.readBytes())
        val apkRequest = (1..server.requestCount).map { server.takeRequest() }
            .first { it.path == "/convoscribe-0.3.0.apk" }
        assertEquals("bytes=100000-", apkRequest.getHeader("Range"))
    }

    @Test
    fun aTransientFailure_isRetried_withAWaitingTick() {
        serve()
        val serving = server.dispatcher
        var failed = false
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/convoscribe-0.3.0.apk" && !failed) {
                    failed = true
                    return MockResponse().setResponseCode(503)
                }
                return serving.dispatch(request)
            }
        }
        var sawWaiting = false
        val apk = runBlocking { downloader.download(offer()) { _, _, waiting -> sawWaiting = sawWaiting || waiting } }
        assertArrayEquals(apkBytes, apk.readBytes())
        assertTrue(sawWaiting)
    }

    @Test
    fun aWrongSize_failsAndDeletesThePart() {
        serve()
        assertThrows(ModelDownloadException::class.java) { download(offer(size = apkBytes.size + 10L)) }
        assertFalse(File(dir, "0.3.0/convoscribe-0.3.0.apk.part").exists())
    }

    @Test
    fun cleanUp_keepsOnlyNewerVersions() {
        File(dir, "0.2.1").mkdirs()
        File(dir, "0.3.0").mkdirs()
        File(dir, "junk").mkdirs()
        assertTrue(downloader.cleanUp(SemVer(0, 2, 1)))
        assertEquals(listOf("0.3.0"), dir.list()!!.toList())
        assertFalse(downloader.cleanUp(SemVer(0, 2, 1)))
    }

    @Test
    fun discard_deletesTheVersionDirectory() {
        File(dir, "0.3.0/convoscribe-0.3.0.apk.part").apply { parentFile?.mkdirs() }.writeBytes(ByteArray(10))
        downloader.discard(offer())
        assertFalse(File(dir, "0.3.0").exists())
    }
}
