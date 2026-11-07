package com.convoscribe.llm

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * LLM endpoint configuration. Built by [AppContainer] from
 * [com.convoscribe.settings.SettingsRepository] at call time so a
 * Settings change (new base URL, new model, new API key) is picked
 * up the next time the screen asks for a token — no client
 * rebuild required.
 *
 * [temperature] is forwarded verbatim. The OpenAI default (1.0) is
 * high; we ship 0.3 to bias towards deterministic summaries, but
 * the settings screen may eventually let the user dial it.
 *
 * [headers] are added to each chat request and [extraBody]'s fields to
 * its JSON (never over `messages` or `stream`): a talking-point check's
 * way of asking for little reasoning (issue #136, [TalkingPointReasoning]).
 */
data class LlmConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val temperature: Double = 0.3,
    val headers: Map<String, String> = emptyMap(),
    val extraBody: JsonObject = JsonObject(emptyMap())
)

/**
 * High-level chat client surface. [stream] returns the token stream
 * for a `/chat/completions` request; [listModels] returns the
 * identifiers the endpoint exposes via `/v1/models`.
 *
 * Implementations are expected to throw [LlmException] for both
 * transport-level and HTTP-level errors so callers have a single
 * exception type to handle in their UI.
 */
interface ChatClient {
    fun stream(messages: List<LlmMessage>): Flow<String>
    suspend fun listModels(): List<String>
}

/**
 * OpenAI-compatible chat client. Talks to a base URL the user
 * entered (typically ending in `/v1`) using OkHttp. Streaming is
 * implemented with `callbackFlow` so the underlying HTTP call is
 * cancelled when the collecting coroutine is cancelled — important
 * because a "Stop generating" UI button must be able to interrupt
 * a multi-second answer without leaving the request hanging.
 *
 * [config] is a lambda, not a snapshot, so settings edits are read
 * lazily on each call. This keeps the wiring in [AppContainer]
 * trivial: one instance, forever, even though the user changes the
 * base URL on the settings screen.
 */
class OpenAiChatClient(
    private val http: OkHttpClient,
    private val config: () -> LlmConfig
) : ChatClient {

    /**
     * POST a `/chat/completions` request and emit each token the
     * server streams back. The returned [Flow] is cold: nothing
     * happens until something starts collecting. Cancellation of
     * the collecting coroutine aborts the HTTP call (`call.cancel()`)
     * so the network does not keep generating tokens we are about
     * to throw away.
     *
     * Throws [LlmException] for:
     *  - missing base URL or model (no HTTP request is issued);
     *  - any non-2xx response (message is
     *    `"HTTP <code> from <url>: <body>"`, body truncated to 300 chars);
     *  - I/O failures (`IOException` wrapped as
     *    `"Network error calling <url>: <exception chain>"`).
     */
    override fun stream(messages: List<LlmMessage>): Flow<String> = callbackFlow {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            throw LlmException("LLM endpoint is not configured")
        }
        val url = cfg.baseUrl.trimEnd('/') + "/chat/completions"

        val requestJson = buildJsonObject {
            put("model", cfg.model)
            put("stream", true)
            put("temperature", cfg.temperature)
            put(
                "messages",
                buildJsonArray {
                    messages.forEach { msg ->
                        add(
                            buildJsonObject {
                                put("role", msg.role)
                                put("content", msg.content)
                            }
                        )
                    }
                }
            )
        }

        val extra = cfg.extraBody.filterKeys { it !in RESERVED_FIELDS }
        val requestBody = JsonObject(requestJson + extra).toString()
            .toRequestBody(JSON_MEDIA_TYPE)

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/event-stream")
            .header("Content-Type", JSON_MEDIA_TYPE.toString())
            .apply {
                if (cfg.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${cfg.apiKey}")
                }
                cfg.headers.forEach { (name, value) -> header(name, value) }
            }
            .post(requestBody)
            .build()

        val call = http.newCall(request)

        // Cancel the in-flight HTTP call if the consumer cancels the
        // flow (e.g. the user taps "Stop generating"). Without this
        // the request would keep streaming tokens no one is reading.
        currentCoroutineContext().job.invokeOnCompletion {
            if (!call.isCanceled()) call.cancel()
        }

        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // callbackFlow is closed by the framework on
                    // close(); an IOException is reported as a
                    // network error so the UI can show a friendly
                    // message instead of the raw stack trace.
                    val wrapped = if (e is CancellationException) {
                        e
                    } else {
                        LlmException("Network error calling $url: ${describeFailure(e)}", cause = e)
                    }
                    close(wrapped)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val code = resp.code
                            val bodyText = resp.body?.string().orEmpty().take(300)
                            close(LlmException("HTTP $code from $url: $bodyText", httpCode = code))
                            return
                        }
                        val source = resp.body?.source()
                        if (source == null) {
                            close(LlmException("HTTP ${resp.code} from $url: empty body", httpCode = resp.code))
                            return
                        }
                        try {
                            while (true) {
                                val line = source.readUtf8Line() ?: break
                                when (val parsed = SseParser.parse(line)) {
                                    is SseParser.Line.Delta -> trySend(parsed.text)
                                    SseParser.Line.Done -> break
                                    SseParser.Line.Ignore -> { /* skip */ }
                                }
                            }
                            close()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: IOException) {
                            close(LlmException("Network error calling $url: ${describeFailure(e)}", cause = e))
                        } catch (e: LlmException) {
                            close(e)
                        }
                    }
                }
            }
        )

        awaitClose {
            if (!call.isCanceled()) call.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * GET `/v1/models` and return the `data[].id` list. The order
     * is whatever the server returned. Runs on [Dispatchers.IO]: the
     * call blocks, and callers (the Settings "Test connection"
     * button) invoke it from the main thread, where Android rejects
     * network access with `NetworkOnMainThreadException`.
     *
     * Throws [LlmException] with the same rules as [stream] (missing
     * config, non-2xx, I/O), plus a body that is not an
     * OpenAI-style model list.
     */
    override suspend fun listModels(): List<String> = withContext(Dispatchers.IO) {
        val cfg = config()
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            throw LlmException("LLM endpoint is not configured")
        }
        val url = cfg.baseUrl.trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply {
                if (cfg.apiKey.isNotBlank()) {
                    header("Authorization", "Bearer ${cfg.apiKey}")
                }
            }
            .get()
            .build()

        try {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val code = resp.code
                    val bodyText = resp.body?.string().orEmpty().take(300)
                    throw LlmException("HTTP $code from $url: $bodyText", httpCode = code)
                }
                parseModelIds(resp.body?.string().orEmpty(), url)
            }
        } catch (e: IOException) {
            throw LlmException("Network error calling $url: ${describeFailure(e)}", cause = e)
        }
    }

    /**
     * `data[].id` from a `/models` body. A body that is not JSON or
     * not an object (an HTML login page from a proxy, a wrong base
     * URL) becomes an [LlmException] quoting its start.
     */
    private fun parseModelIds(text: String, url: String): List<String> {
        val data = try {
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray
        } catch (e: IllegalArgumentException) {
            // Covers kotlinx.serialization's SerializationException
            // and the non-object `jsonObject` / `jsonArray` casts.
            throw LlmException(
                "Unexpected response from $url (not an OpenAI-style model list): ${text.take(200)}",
                cause = e
            )
        } ?: return emptyList()
        return data.mapNotNull { element ->
            (element as? kotlinx.serialization.json.JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Fields [LlmConfig.extraBody] may not replace: the client streams the messages it was given. */
        val RESERVED_FIELDS = setOf("messages", "stream")
    }
}
