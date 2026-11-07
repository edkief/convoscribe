package com.convoscribe.llm

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [OpenAiChatClient] against an in-process
 * `MockWebServer`. The server is configured per test with the
 * `Content-Type: text/event-stream` header and a body made of
 * SSE lines separated by `\n\n` (the SSE event boundary).
 *
 * The tests cover every acceptance criterion called out in
 * TASK-23: token streaming, header presence/absence, HTTP error
 * propagation, missing-config short-circuit and `listModels`.
 */
class OpenAiChatClientTest {

    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        http = OkHttpClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun stream_emitsDeltaTokensUntilDone() = runBlocking {
        // Two token chunks plus the [DONE] sentinel.
        val body = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}

            data: {"choices":[{"delta":{"content":"lo"}}]}

            data: [DONE]

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "gpt-test"
            )
        }

        val tokens = client.stream(
            listOf(LlmMessage(role = LlmMessage.ROLE_USER, content = "hi"))
        ).toList()

        assertEquals(listOf("Hel", "lo"), tokens)

        val recorded = server.takeRequest()
        assertEquals("/v1/chat/completions", recorded.path)
        val sentBody = recorded.body.readUtf8()
        assertTrue(
            "expected body to have stream=true, was: $sentBody",
            sentBody.contains("\"stream\":true")
        )
        assertTrue(
            "expected body to mention model name, was: $sentBody",
            sentBody.contains("\"model\":\"gpt-test\"")
        )
        assertEquals("text/event-stream", recorded.getHeader("Accept"))
    }

    @Test
    fun stream_emitsAuthorizationHeaderWhenKeyIsNotBlank() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "k",
                model = "m"
            )
        }
        // Trigger the flow.
        client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()

        val recorded = server.takeRequest()
        assertEquals("Bearer k", recorded.getHeader("Authorization"))
    }

    @Test
    fun stream_sendsTheConfigsHeadersAndExtraFields_butNeverOverTheMessagesOrStream() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )
        val extra = Json.parseToJsonElement(
            """{"chat_template_kwargs":{"enable_thinking":false},"stream":false,"messages":[]}"""
        ).jsonObject
        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "m",
                headers = mapOf(TalkingPointReasoning.HEADER to "low"),
                extraBody = extra
            )
        }
        client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()

        val recorded = server.takeRequest()
        assertEquals("low", recorded.getHeader(TalkingPointReasoning.HEADER))
        val sent = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("""{"enable_thinking":false}""", sent["chat_template_kwargs"].toString())
        assertEquals("true", sent["stream"].toString())
        assertEquals(1, sent["messages"]!!.jsonArray.size)
    }

    @Test
    fun stream_omitsAuthorizationHeaderWhenKeyIsBlank() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: [DONE]\n\n")
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "m"
            )
        }
        client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()

        val recorded = server.takeRequest()
        assertNull(
            "Authorization header must be absent when key is blank",
            recorded.getHeader("Authorization")
        )
    }

    @Test
    fun stream_nonOkResponseRaisesLlmExceptionWithHttpCode() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error":"bad key"}""")
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "anything",
                model = "m"
            )
        }

        val ex = assertThrows(LlmException::class.java) {
            runBlocking {
                client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()
            }
        }
        assertEquals(401, ex.httpCode)
        assertTrue(
            "expected message to mention 'bad key', was: ${ex.message}",
            ex.message!!.contains("bad key")
        )
    }

    @Test
    fun stream_blankBaseUrlRaisesLlmExceptionWithoutHttpRequest() = runBlocking {
        val client = OpenAiChatClient(http) {
            LlmConfig(baseUrl = "", apiKey = "", model = "m")
        }

        val ex = assertThrows(LlmException::class.java) {
            runBlocking {
                client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()
            }
        }
        assertEquals("LLM endpoint is not configured", ex.message)
        assertNull(ex.httpCode)

        // No request must have been sent: MockWebServer's
        // requestCount goes up only when a request lands.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun stream_blankModelRaisesLlmExceptionWithoutHttpRequest() = runBlocking {
        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = ""
            )
        }

        val ex = assertThrows(LlmException::class.java) {
            runBlocking {
                client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()
            }
        }
        assertEquals("LLM endpoint is not configured", ex.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun listModels_parsesDataIds() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"data":[{"id":"a"},{"id":"b"}]}""")
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "ignored-but-required-for-config-check"
            )
        }

        val models = client.listModels()
        assertEquals(listOf("a", "b"), models)
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test
    fun listModels_nonOkResponseRaisesLlmExceptionWithHttpCode() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setHeader("Content-Type", "application/json")
                .setBody("internal")
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "m"
            )
        }

        val ex = assertThrows(LlmException::class.java) {
            runBlocking { client.listModels() }
        }
        assertEquals(500, ex.httpCode)
        assertTrue(
            "expected message to mention HTTP 500, was: ${ex.message}",
            ex.message!!.startsWith("HTTP 500")
        )
    }

    @Test
    fun stream_errorFrameInBodyRaisesLlmException() = runBlocking {
        // The streaming protocol carries errors inside a 200 OK
        // body as `data: {"error":{...}}`; the parser must surface
        // the message and abort the flow.
        val body = """
            data: {"choices":[{"delta":{"content":"Hel"}}]}

            data: {"error":{"message":"rate limited"}}

        """.trimIndent()
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(body)
        )

        val client = OpenAiChatClient(http) {
            LlmConfig(
                baseUrl = server.url("/v1").toString(),
                apiKey = "",
                model = "m"
            )
        }

        val ex = assertThrows(LlmException::class.java) {
            runBlocking {
                client.stream(listOf(LlmMessage(LlmMessage.ROLE_USER, "hi"))).toList()
            }
        }
        assertEquals("rate limited", ex.message)
    }

    /**
     * Regression: listModels ran its blocking call on the caller's
     * thread, so Settings' "Test connection" (main thread) failed with
     * NetworkOnMainThreadException. The request must run elsewhere.
     */
    @Test
    fun listModels_runsTheRequestOffTheCallingThread() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        var requestThread: String? = null
        val recording = http.newBuilder()
            .addInterceptor { chain ->
                requestThread = Thread.currentThread().name
                chain.proceed(chain.request())
            }
            .build()
        val client = OpenAiChatClient(recording) {
            LlmConfig(baseUrl = server.url("/v1").toString(), apiKey = "", model = "m")
        }
        val callerThread = Thread.currentThread().name

        client.listModels()

        assertNotNull(requestThread)
        assertNotEquals(callerThread, requestThread)
    }

    @Test
    fun listModels_unreachableServerNamesUrlAndException() = runBlocking {
        val url = server.url("/v1").toString()
        server.shutdown()
        val client = OpenAiChatClient(http) { LlmConfig(baseUrl = url, apiKey = "", model = "m") }

        val ex = assertThrows(LlmException::class.java) { runBlocking { client.listModels() } }

        val message = ex.message!!
        assertTrue(message, message.startsWith("Network error calling $url/models: "))
        assertTrue(message, message.contains("ConnectException"))
    }

    @Test
    fun listModels_nonJsonBodyRaisesUnexpectedResponse() = runBlocking {
        server.enqueue(MockResponse().setBody("<html>Sign in</html>"))
        val client = OpenAiChatClient(http) {
            LlmConfig(baseUrl = server.url("/v1").toString(), apiKey = "", model = "m")
        }

        val ex = assertThrows(LlmException::class.java) { runBlocking { client.listModels() } }

        val message = ex.message!!
        assertTrue(message, message.startsWith("Unexpected response from "))
        assertTrue(message, message.contains("<html>Sign in</html>"))
    }
}
