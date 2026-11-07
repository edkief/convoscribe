package com.convoscribe.decision

import com.convoscribe.llm.LlmException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpDecisionClientTest {

    private lateinit var server: MockWebServer
    private var config = DecisionConfig("", "", "")
    private val client by lazy { HttpDecisionClient(OkHttpClient()) { config } }

    private val question = DecisionQuestion("Which point?", mapOf("1" to "Budget", "none" to "none of these"))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
        config = DecisionConfig(server.url("/laya/").toString(), "laya-key", "english")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun results(count: Int, choice: String = "1") = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"results":[""" + (1..count).joinToString(",") {
                """{"answers":{"q":{"type":"choice","choice":"$choice","probabilities":{"1":0.8,"none":0.2}}},""" +
                    """"usage":{"input_tokens":30,"truncated":false},"routing":{"model":"english"}}"""
            } + """],"total_usage":{"input_tokens":30}}"""
        )

    @Test
    fun decide_sendsOneBatch_withTheQuestionsAsChoices_andReadsTheAnswers() = runBlocking {
        server.enqueue(results(2))

        val answers = client.decide(listOf("[00:05] Budget first.", "[00:09] Hello."), mapOf("q" to question))

        assertEquals(2, answers.size)
        assertEquals(DecisionAnswer("1", mapOf("1" to 0.8, "none" to 0.2)), answers[0]["q"])
        val request = server.takeRequest()
        assertEquals("/laya/v1/systemone/batch", request.path)
        assertEquals("Bearer laya-key", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(
            listOf("[00:05] Budget first.", "[00:09] Hello."),
            body["states"]!!.jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        val q = body["questions"]!!.jsonObject["q"]!!.jsonObject
        assertEquals("choice", q["type"]!!.jsonPrimitive.content)
        assertEquals("Which point?", q["instructions"]!!.jsonPrimitive.content)
        assertEquals("Budget", q["criteria"]!!.jsonObject["1"]!!.jsonPrimitive.content)
        assertEquals("english", body["model"]!!.jsonPrimitive.content)
    }

    @Test
    fun decide_withoutAModelOrKey_sendsNeither() = runBlocking {
        config = config.copy(apiKey = "", model = "")
        server.enqueue(results(1))

        client.decide(listOf("x"), mapOf("q" to question))

        val request = server.takeRequest()
        assertNull(request.getHeader("Authorization"))
        assertFalse(Json.parseToJsonElement(request.body.readUtf8()).jsonObject.containsKey("model"))
    }

    @Test
    fun decide_splitsMoreThan64StatesIntoSeveralCalls_inOrder() = runBlocking {
        server.enqueue(results(64))
        server.enqueue(results(6, choice = "none"))

        val answers = client.decide((1..70).map { "line $it" }, mapOf("q" to question))

        assertEquals(70, answers.size)
        assertEquals("1", answers[63]["q"]!!.choice)
        assertEquals("none", answers[64]["q"]!!.choice)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun decide_withNothingToAsk_callsNothing() = runBlocking {
        assertEquals(emptyList<Map<String, DecisionAnswer>>(), client.decide(emptyList(), mapOf("q" to question)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun anOldServerWithoutBatch_saysWhatItNeeds() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Not Found"}"""))
        val e = failure { client.decide(listOf("x"), mapOf("q" to question)) }
        assertEquals(404, e.httpCode)
        assertTrue(e.message!!.contains("laya-serve 0.4.22"))
    }

    @Test
    fun aRefusedRequest_carriesTheServersDetail() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"detail":"question 'q': no 'instructions'"}"""))
        val e = failure { client.decide(listOf("x"), mapOf("q" to question)) }
        assertEquals(422, e.httpCode)
        assertTrue(e.message!!.contains("no 'instructions'"))
    }

    @Test
    fun anAnswerOfTheWrongShape_isAFailure() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"results":[]}"""))
        assertTrue(
            failure { client.decide(listOf("x"), mapOf("q" to question)) }.message!!.startsWith("Unexpected answer")
        )
        server.enqueue(MockResponse().setBody("""{"results":[{"answers":{}}]}"""))
        assertTrue(failure { client.decide(listOf("x"), mapOf("q" to question)) }.message!!.contains("no answer to q"))
    }

    @Test
    fun notConfigured_isAFailure_withoutACall() = runBlocking {
        config = DecisionConfig("", "", "")
        assertEquals("Decision service is not configured", failure { client.ping() }.message)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun ping_returnsTheModelThatAnswered() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[{"answers":{"test":{"choice":"yes","probabilities":{"yes":0.6,"no":0.4}}},""" +
                    """"routing":{"model":"multilingual"}}]}"""
            )
        )
        assertEquals("multilingual", client.ping())
    }

    private suspend fun failure(block: suspend () -> Unit): LlmException {
        try {
            block()
        } catch (e: LlmException) {
            return e
        }
        fail("expected an LlmException")
        throw AssertionError()
    }
}
