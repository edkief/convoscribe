package com.convoscribe.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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
import org.junit.Before
import org.junit.Test

/**
 * [DeviceSignInClient] against a fake server and proxy (issue #95):
 * what each answer of the sign-in route and of authz-proxy's key
 * request endpoints becomes, and what the app sends.
 */
class DeviceSignInClientTest {

    private lateinit var server: MockWebServer
    private val client = DeviceSignInClient(OkHttpClient())

    private val base: String get() = server.url("/").toString().trimEnd('/')
    private val offer: SignInOffer get() = SignInOffer(base, "convoscribe", listOf("convoscribe"))

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    @Test
    fun `the server's sign-in names the proxy, the client and the roles`() = runBlocking {
        server.enqueue(
            json("""{"authzProxy":{"url":"https://auth.example.com/","clientId":"convoscribe","roles":["a","b"]}}""")
        )
        val got = client.fetchOffer("$base/")
        assertEquals(SignInOffer("https://auth.example.com", "convoscribe", listOf("a", "b")), got)
        val request = server.takeRequest()
        assertEquals("/api/public/v1/sign-in", request.path)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `roles left out mean all of them`() = runBlocking {
        server.enqueue(json("""{"authzProxy":{"url":"https://auth.example.com","clientId":"c","roles":null}}"""))
        assertNull(client.fetchOffer(base)!!.roles)
    }

    @Test
    fun `no sign-in when the server offers none, is older, or ForwardAuth answers instead`() = runBlocking {
        server.enqueue(json("""{"authzProxy":null}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html>Sign in</html>"))
        server.enqueue(json("""{"authzProxy":{"url":"javascript:alert(1)","clientId":"c"}}"""))
        repeat(5) { assertNull(client.fetchOffer(base)) }
    }

    @Test
    fun `no sign-in when the server is unreachable`() = runBlocking {
        server.shutdown()
        assertNull(client.fetchOffer(base))
    }

    @Test
    fun `start asks for a key that never expires, returning to the app`() = runBlocking {
        server.enqueue(
            json(
                """{"request_secret":"rs","approval_url":"$APPROVAL","expires_in":600,"interval":5}""",
                201
            )
        )
        val result = client.start(offer, "ConvoScribe on Pixel 9", "Syncs", "convoscribe://api-key", "st")
        assertEquals(
            StartResult.Started(StartedKeyRequest("rs", APPROVAL, 5)),
            result
        )
        val request = server.takeRequest()
        assertEquals("/api/api-key-requests", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("convoscribe", body["client_id"]!!.jsonPrimitive.content)
        assertEquals("ConvoScribe on Pixel 9", body["purpose"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["expires_in_days"])
        assertEquals(listOf("convoscribe"), body["roles"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("convoscribe://api-key", body["return_url"]!!.jsonPrimitive.content)
        assertEquals("st", body["state"]!!.jsonPrimitive.content)
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `start leaves roles out to ask for all of them`() = runBlocking {
        server.enqueue(json("""{"request_secret":"rs","approval_url":"u","interval":5}""", 201))
        client.start(offer.copy(roles = null), "p", "d", "convoscribe://api-key", "s")
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertFalse(body.containsKey("roles"))
    }

    @Test
    fun `start reports a refusal with the proxy's reason`() = runBlocking {
        server.enqueue(json("""{"error":"invalid_client","error_description":"unknown client"}""", 401))
        val result = client.start(offer, "p", "d", "convoscribe://api-key", "s")
        assertTrue(result is StartResult.Failed && result.message.contains("unknown client"))
    }

    @Test
    fun `status maps each answer`() = runBlocking {
        for (status in listOf(
            "pending",
            "approved",
            "declined",
            "expired"
        )) server.enqueue(json("""{"status":"$status"}"""))
        server.enqueue(json("""{"error":"not_found"}""", 404))
        server.enqueue(json("""{"error":"slow_down"}""", 429))
        server.enqueue(MockResponse().setResponseCode(503))
        val got = List(7) { client.status(base, "convoscribe", "rs") }
        assertEquals(KeyRequestResult.Pending, got[0])
        assertEquals(KeyRequestResult.Approved, got[1])
        assertEquals(KeyRequestResult.Declined, got[2])
        assertEquals(KeyRequestResult.Expired, got[3])
        assertEquals(KeyRequestResult.Gone, got[4])
        assertEquals(KeyRequestResult.SlowDown, got[5])
        assertTrue(got[6] is KeyRequestResult.Failed)
        val first = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("rs", first["request_secret"]!!.jsonPrimitive.content)
        assertEquals("convoscribe", first["client_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `finalize collects the key once, and maps the refusals`() = runBlocking {
        server.enqueue(json("""{"api_key":"sk_authz_abc_def","key":{"keyId":"abc"}}"""))
        server.enqueue(json("""{"error":"not_approved"}""", 409))
        server.enqueue(json("""{"error":"declined"}""", 403))
        server.enqueue(json("""{"error":"expired"}""", 410))
        server.enqueue(json("""{"error":"not_found"}""", 404))
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(KeyRequestResult.Issued("sk_authz_abc_def"), client.finalize(base, "c", "rs"))
        assertEquals("/api/api-key-requests/finalize", server.takeRequest().path)
        assertEquals(KeyRequestResult.Pending, client.finalize(base, "c", "rs"))
        assertEquals(KeyRequestResult.Declined, client.finalize(base, "c", "rs"))
        assertEquals(KeyRequestResult.Expired, client.finalize(base, "c", "rs"))
        assertEquals(KeyRequestResult.Gone, client.finalize(base, "c", "rs"))
        assertTrue(client.finalize(base, "c", "rs") is KeyRequestResult.Failed)
    }

    private companion object {
        const val APPROVAL = "https://auth.example.com/api-keys/request?token=t"
    }
}
