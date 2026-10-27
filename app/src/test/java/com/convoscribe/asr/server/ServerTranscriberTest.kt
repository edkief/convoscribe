package com.convoscribe.asr.server

import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ServerTranscriber] against an in-process `MockWebServer` (issue #58). */
class ServerTranscriberTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var wav: File
    private var config = ServerAsrConfig(baseUrl = "", apiKey = "", model = "")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        config = ServerAsrConfig(baseUrl = server.url("/v1/").toString(), apiKey = "", model = "parakeet")
        wav = tmp.newFile("session.wav").apply { writeBytes(ByteArray(200_000) { (it % 7).toByte() }) }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun transcriber() = ServerTranscriber(OkHttpClient()) { config }

    @Test
    fun uploadsTheWavAndParsesTheReply() = runBlocking {
        server.enqueue(MockResponse().setBody(REPLY))
        val progress = mutableListOf<Float>()

        val finals = transcriber().transcribe(wav, "fr") { progress += it }

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/audio/transcriptions", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        assertNull(request.getHeader("Authorization"))
        val body = request.body.readUtf8()
        assertTrue(body.contains("name=\"file\"; filename=\"session.wav\""))
        assertTrue(body.contains(field("response_format", "verbose_json")))
        assertTrue(body.contains(field("timestamp_granularities[]", "word")))
        assertTrue(body.contains(field("timestamp_granularities[]", "segment")))
        assertTrue(body.contains(field("language", "fr")))
        assertTrue(body.contains(field("model", "parakeet")))
        assertTrue(request.bodySize > wav.length())

        assertEquals("Ask not.", finals.single().text)
        assertEquals(2, finals.single().tokens.size)
        assertEquals(1f, progress.last())
        assertTrue(progress.any { it in 0.5f..ServerTranscriber.UPLOAD_SHARE })
        assertEquals(progress.sorted(), progress)
        assertEquals("server", transcriber().describe().engine)
    }

    @Test
    fun sendsTheApiKey() = runBlocking {
        config = config.copy(apiKey = "sk-test")
        server.enqueue(MockResponse().setBody(REPLY))

        transcriber().transcribe(wav, "en")

        assertEquals("Bearer sk-test", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun blankModelUsesTheServersFirstModelAndDescribesIt() = runBlocking {
        config = config.copy(model = "")
        server.enqueue(MockResponse().setBody("""{"object":"list","data":[{"id":"parakeet-tdt-0.6b-v3"}]}"""))
        server.enqueue(MockResponse().setBody(REPLY))
        val transcriber = transcriber()

        transcriber.transcribe(wav, "en")

        assertEquals("/v1/models", server.takeRequest().path)
        assertTrue(server.takeRequest().body.readUtf8().contains(field("model", "parakeet-tdt-0.6b-v3")))
        assertEquals("parakeet-tdt-0.6b-v3", transcriber.describe().model)
    }

    @Test
    fun blankModelAndNoModelListSendsNoModel() = runBlocking {
        config = config.copy(model = "")
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setBody(REPLY))

        transcriber().transcribe(wav, "en")

        server.takeRequest()
        assertFalse(server.takeRequest().body.readUtf8().contains("name=\"model\""))
    }

    @Test
    fun httpErrorIsAServerError() {
        server.enqueue(
            MockResponse().setResponseCode(400).setBody("""{"error":{"message":"could not decode audio"}}""")
        )

        val e = assertThrows(ServerAsrException::class.java) {
            runBlocking { transcriber().transcribe(wav, "en") }
        }

        assertEquals(400, e.httpCode)
        assertTrue(e.message!!.startsWith("HTTP 400 from "))
        assertTrue(e.message!!.contains("could not decode audio"))
    }

    @Test
    fun malformedReplyIsAServerError() {
        server.enqueue(MockResponse().setBody("<html>proxy login</html>"))

        assertThrows(ServerAsrException::class.java) {
            runBlocking { transcriber().transcribe(wav, "en") }
        }
    }

    @Test
    fun unreachableServerIsANetworkError() {
        server.shutdown()

        val e = assertThrows(ServerAsrException::class.java) {
            runBlocking { transcriber().transcribe(wav, "en") }
        }

        assertTrue(e.message!!.startsWith("Network error calling "))
    }

    @Test
    fun notSetUpMakesNoRequest() {
        config = config.copy(baseUrl = "")

        assertThrows(ServerAsrException::class.java) {
            runBlocking { transcriber().transcribe(wav, "en") }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun cancellingAbortsTheCall() = runBlocking {
        server.enqueue(MockResponse().setBody(REPLY).setHeadersDelay(10, TimeUnit.SECONDS))
        val job = async { transcriber().transcribe(wav, "en") }
        server.takeRequest(5, TimeUnit.SECONDS)

        val started = System.nanoTime()
        job.cancel()
        runCatching { job.await() }

        assertTrue(job.isCancelled)
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5_000)
    }

    @Test
    fun listModelsReturnsTheIds() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[{"id":"a"},{"id":"b"}]}"""))

        assertEquals(listOf("a", "b"), transcriber().listModels())
        assertEquals("/v1/models", server.takeRequest().path)
    }

    private fun field(name: String, value: String) = "name=\"$name\"\r\nContent-Length: ${value.length}\r\n\r\n$value"

    private companion object {
        const val REPLY = """{"task":"transcribe","duration":1.0,"text":"Ask not.",
            "segments":[{"id":0,"start":0.1,"end":0.9,"text":"Ask not."}],
            "words":[{"word":"Ask","start":0.1,"end":0.4},{"word":"not.","start":0.4,"end":0.9}]}"""
    }
}
