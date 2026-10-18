package com.convoscribe.models

import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ResumableFetch] against a [MockWebServer]: resume, fallback restart and error classes. */
class ResumableFetchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var fetch: ResumableFetch
    private lateinit var part: File
    private val body = ByteArray(10_000) { (it % 251).toByte() }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        fetch = ResumableFetch(OkHttpClient())
        part = File(tmp.root, "dir/file.bin.part")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url() = server.url("/file.bin").toString()

    private fun run(expectedSize: Long? = body.size.toLong(), onBytes: (Long) -> Unit = {}) = runBlocking {
        fetch.fetch(url(), part, expectedSize, "file.bin") { onBytes(it) }
    }

    private fun writePart(bytes: Int) {
        part.parentFile?.mkdirs()
        part.writeBytes(body.copyOfRange(0, bytes))
    }

    @Test
    fun freshDownload_writesWholeBody_withoutRangeHeader() {
        server.enqueue(MockResponse().setBody(Buffer().write(body)))
        val ticks = mutableListOf<Long>()

        run { ticks += it }

        assertArrayEquals(body, part.readBytes())
        assertNull(server.takeRequest().getHeader("Range"))
        assertEquals(body.size.toLong(), ticks.last())
    }

    @Test
    fun partialFile_sendsRange_andAppendsA206() {
        writePart(4_000)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 4000-9999/10000")
                .setBody(Buffer().write(body.copyOfRange(4_000, body.size)))
        )
        val ticks = mutableListOf<Long>()

        run { ticks += it }

        assertEquals("bytes=4000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(body, part.readBytes())
        assertEquals(10_000L, ticks.last())
    }

    @Test
    fun serverIgnoresRange_200_restartsTheFile() {
        writePart(4_000)
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        run()

        assertEquals("bytes=4000-", server.takeRequest().getHeader("Range"))
        assertArrayEquals(body, part.readBytes())
    }

    @Test
    fun partLargerThanExpected_isDiscarded_andFetchedFromZero() {
        part.parentFile?.mkdirs()
        part.writeBytes(ByteArray(12_000))
        server.enqueue(MockResponse().setBody(Buffer().write(body)))

        run()

        assertNull(server.takeRequest().getHeader("Range"))
        assertArrayEquals(body, part.readBytes())
    }

    @Test
    fun completePart_makesNoRequest() {
        writePart(body.size)

        run()

        assertEquals(0, server.requestCount)
    }

    @Test
    fun rangeAtTheWrongOffset_dropsThePart_andIsTransient() {
        writePart(4_000)
        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 0-9999/10000")
                .setBody(Buffer().write(body))
        )

        val ex = assertThrows(ModelDownloadException::class.java) { run() }

        assertTrue(ex.transient)
        assertFalse(part.exists())
    }

    @Test
    fun http404_isPermanent_andKeepsThePart() {
        writePart(4_000)
        server.enqueue(MockResponse().setResponseCode(404))

        val ex = assertThrows(ModelDownloadException::class.java) { run() }

        assertEquals("HTTP 404 for file.bin", ex.message)
        assertFalse(ex.transient)
        assertEquals(4_000L, part.length())
    }

    @Test
    fun http503_408_429_areTransient() {
        for (code in listOf(503, 408, 429)) {
            server.enqueue(MockResponse().setResponseCode(code))
            val ex = assertThrows(ModelDownloadException::class.java) { run() }
            assertTrue("HTTP $code should be transient", ex.transient)
        }
    }

    @Test
    fun connectionDroppedMidBody_isTransient_andKeepsTheBytesReceived() {
        server.enqueue(
            MockResponse().setBody(Buffer().write(body))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val ex = assertThrows(ModelDownloadException::class.java) { run() }

        assertTrue(ex.transient)
        assertTrue("part should be kept", part.exists())
        assertTrue(part.length() < body.size)
    }
}
