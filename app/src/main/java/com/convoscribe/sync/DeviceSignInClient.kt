package com.convoscribe.sync

import com.convoscribe.llm.describeFailure
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** How a device signs in to a server (issue #95), as the server advertises it. */
data class SignInOffer(val proxyUrl: String, val clientId: String, val roles: List<String>?)

/** A key request the proxy accepted: where the user approves it, and how to collect the key. */
data class StartedKeyRequest(val requestSecret: String, val approvalUrl: String, val intervalSeconds: Int)

/** The answer of [DeviceSignInClient.start]. */
sealed interface StartResult {
    data class Started(val request: StartedKeyRequest) : StartResult
    data class Failed(val message: String) : StartResult
}

/** Where a key request stands, from [DeviceSignInClient.status] and [DeviceSignInClient.finalize]. */
sealed interface KeyRequestResult {
    /** The user has not answered yet. */
    data object Pending : KeyRequestResult

    /** Approved; [DeviceSignInClient.finalize] collects the key. */
    data object Approved : KeyRequestResult

    /** The key, given once. */
    data class Issued(val apiKey: String) : KeyRequestResult
    data object Declined : KeyRequestResult
    data object Expired : KeyRequestResult

    /** Unknown to the proxy: already collected, or long gone. */
    data object Gone : KeyRequestResult

    /** Asked too often: wait longer before the next check. */
    data object SlowDown : KeyRequestResult

    /** Not reached, or an unexpected answer; worth trying again. */
    data class Failed(val message: String) : KeyRequestResult
}

/** What [com.convoscribe.ui.settings.SettingsViewModel] needs of [DeviceSignInClient]; faked in tests. */
interface DeviceSignInApi {
    suspend fun fetchOffer(serverUrl: String): SignInOffer?

    suspend fun start(
        offer: SignInOffer,
        purpose: String,
        description: String,
        returnUrl: String,
        state: String
    ): StartResult

    suspend fun status(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult

    suspend fun finalize(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult
}

/**
 * Device sign-in (issue #95) through authz-proxy's API key requests:
 * [fetchOffer] asks the ConvoScribe server how to sign in
 * (`GET /api/public/v1/sign-in`, no key), then [start], [status] and
 * [finalize] talk to the proxy as its public client, which sends no
 * secret. The user approves the request in a browser; the
 * request secret never leaves the app, so a seen approval link cannot
 * collect the key.
 */
class DeviceSignInClient(private val http: OkHttpClient) : DeviceSignInApi {

    /**
     * The server's sign-in, or `null` when it offers none: `api-key`
     * mode, sign-in not configured, a server from before #95 (`404`),
     * or ForwardAuth answering in its place (a sign-in page or `401`)
     * because `/api/public/` is not let through.
     */
    override suspend fun fetchOffer(serverUrl: String): SignInOffer? = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        val answer = try {
            call(Request.Builder().url("$base/api/public/v1/sign-in").get())
        } catch (e: IOException) {
            return@withContext null
        } catch (e: IllegalArgumentException) {
            return@withContext null
        }
        if (answer.code != 200) return@withContext null
        val proxy = try {
            json.decodeFromString<OfferBody>(answer.text).authzProxy
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        } ?: return@withContext null
        val url = proxy.url.trim().trimEnd('/')
        if (!url.startsWith("https://") && !url.startsWith("http://")) return@withContext null
        if (proxy.clientId.isBlank()) return@withContext null
        SignInOffer(proxyUrl = url, clientId = proxy.clientId, roles = proxy.roles)
    }

    /** Start a key request that never expires, returning to [returnUrl] with [state]. */
    override suspend fun start(
        offer: SignInOffer,
        purpose: String,
        description: String,
        returnUrl: String,
        state: String
    ): StartResult = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("client_id", offer.clientId)
            put("purpose", purpose.take(MAX_PURPOSE))
            put("description", description)
            put("expires_in_days", JsonNull)
            offer.roles?.let { roles -> putJsonArray("roles") { roles.forEach { add(JsonPrimitive(it)) } } }
            put("return_url", returnUrl)
            put("state", state)
        }
        val answer = try {
            post("${offer.proxyUrl}/api/api-key-requests", body.toString())
        } catch (e: IOException) {
            return@withContext StartResult.Failed("Network error: ${describeFailure(e)}")
        } catch (e: IllegalArgumentException) {
            return@withContext StartResult.Failed("Not a valid sign-in address")
        }
        when (answer.code) {
            201, 200 -> {}
            429 -> return@withContext StartResult.Failed(
                "Too many sign-ins from this network; try again in a few minutes"
            )
            else -> return@withContext StartResult.Failed(
                "The sign-in server refused the request (HTTP ${answer.code}${errorOf(
                    answer.text
                )?.let { ": $it" }.orEmpty()})"
            )
        }
        try {
            val started = json.decodeFromString<StartBody>(answer.text)
            StartResult.Started(
                StartedKeyRequest(
                    requestSecret = started.request_secret,
                    approvalUrl = started.approval_url,
                    intervalSeconds = started.interval.coerceAtLeast(1)
                )
            )
        } catch (e: SerializationException) {
            StartResult.Failed("Unexpected answer from the sign-in server")
        } catch (e: IllegalArgumentException) {
            StartResult.Failed("Unexpected answer from the sign-in server")
        }
    }

    /** Where the request stands; [KeyRequestResult.Approved] means [finalize] can collect the key. */
    override suspend fun status(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult =
        withContext(Dispatchers.IO) {
            val answer = try {
                post("$proxyUrl/api/api-key-requests/status", secretBody(clientId, requestSecret))
            } catch (e: IOException) {
                return@withContext KeyRequestResult.Failed("Network error: ${describeFailure(e)}")
            } catch (e: IllegalArgumentException) {
                return@withContext KeyRequestResult.Failed("Not a valid sign-in address")
            }
            when (answer.code) {
                200 -> when (fieldOf(answer.text, "status")) {
                    "pending" -> KeyRequestResult.Pending
                    "approved" -> KeyRequestResult.Approved
                    "declined" -> KeyRequestResult.Declined
                    "expired" -> KeyRequestResult.Expired
                    else -> KeyRequestResult.Failed("Unexpected answer from the sign-in server")
                }
                404 -> KeyRequestResult.Gone
                429 -> KeyRequestResult.SlowDown
                else -> KeyRequestResult.Failed("HTTP ${answer.code} from the sign-in server")
            }
        }

    /** Collect the key of an approved request. It is given once: store it before anything else. */
    override suspend fun finalize(proxyUrl: String, clientId: String, requestSecret: String): KeyRequestResult =
        withContext(Dispatchers.IO) {
            val answer = try {
                post("$proxyUrl/api/api-key-requests/finalize", secretBody(clientId, requestSecret))
            } catch (e: IOException) {
                return@withContext KeyRequestResult.Failed("Network error: ${describeFailure(e)}")
            } catch (e: IllegalArgumentException) {
                return@withContext KeyRequestResult.Failed("Not a valid sign-in address")
            }
            when (answer.code) {
                200 -> fieldOf(answer.text, "api_key")?.takeIf { it.isNotBlank() }
                    ?.let { KeyRequestResult.Issued(it) }
                    ?: KeyRequestResult.Failed("Unexpected answer from the sign-in server")
                409 -> KeyRequestResult.Pending
                403 -> KeyRequestResult.Declined
                410 -> KeyRequestResult.Expired
                404 -> KeyRequestResult.Gone
                429 -> KeyRequestResult.SlowDown
                else -> KeyRequestResult.Failed("HTTP ${answer.code} from the sign-in server")
            }
        }

    private fun secretBody(clientId: String, requestSecret: String): String = buildJsonObject {
        put("client_id", clientId)
        put("request_secret", requestSecret)
    }.toString()

    private fun fieldOf(text: String, name: String): String? = try {
        (json.parseToJsonElement(text) as? JsonObject)
            ?.get(name)?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun errorOf(text: String): String? = fieldOf(text, "error_description") ?: fieldOf(text, "error")

    private class Answer(val code: Int, val text: String)

    private fun post(url: String, body: String): Answer =
        call(Request.Builder().url(url).post(body.toRequestBody(JSON)))

    private fun call(builder: Request.Builder): Answer {
        val request = builder.header("Accept", "application/json").build()
        return http.newCall(request).execute().use { Answer(it.code, it.body?.string().orEmpty()) }
    }

    @Serializable
    private data class ProxyBody(val url: String, val clientId: String, val roles: List<String>? = null)

    @Serializable
    private data class OfferBody(val authzProxy: ProxyBody? = null)

    @Suppress("PropertyName")
    @Serializable
    private data class StartBody(val request_secret: String, val approval_url: String, val interval: Int = 5)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val JSON = "application/json".toMediaType()

        /** authz-proxy's limit on a request's purpose, which becomes the key's name. */
        const val MAX_PURPOSE = 100
    }
}
