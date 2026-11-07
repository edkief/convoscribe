package com.convoscribe.decision

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.llm.LlmException
import com.convoscribe.llm.describeFailure
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * A `choice` question for the decision service (issue #101): the text
 * the model answers, and the options as label to description.
 */
data class DecisionQuestion(val instructions: String, val options: Map<String, String>)

/** The decision service's answer to a [DecisionQuestion]: the option it chose, and every option's probability. */
data class DecisionAnswer(val choice: String, val probabilities: Map<String, Double>) {
    fun probabilityOf(option: String): Double = probabilities[option] ?: 0.0
}

/**
 * A laya decision service (issue #101), spoken to over laya-serve's
 * Jev-compatible API. Failures are [LlmException]s, like the language
 * model's, so screens and the talking-point checker handle both alike.
 */
interface DecisionClient {
    /**
     * Ask every one of [questions] about each of [states]: the answers,
     * by question id, one map per state in the order given.
     */
    suspend fun decide(
        states: List<String>,
        questions: Map<String, DecisionQuestion>
    ): List<Map<String, DecisionAnswer>>

    /** One small decision, for *Test connection*: the laya model that answered it. */
    suspend fun ping(): String
}

/**
 * [DecisionClient] over HTTP: `POST <base>/v1/systemone/batch`
 * (laya-serve 0.4.22 or later), at most [MAX_STATES] states per call.
 * [config] is read on every call, as for the language model, so a
 * Settings change applies to the next check. A call is cancelled with
 * its coroutine.
 */
class HttpDecisionClient(
    private val http: OkHttpClient,
    private val config: () -> DecisionConfig
) : DecisionClient {

    override suspend fun decide(
        states: List<String>,
        questions: Map<String, DecisionQuestion>
    ): List<Map<String, DecisionAnswer>> {
        if (states.isEmpty() || questions.isEmpty()) return states.map { emptyMap() }
        val cfg = config()
        if (cfg.baseUrl.isBlank()) throw LlmException("Decision service is not configured")
        return states.chunked(MAX_STATES).flatMap { chunk ->
            batch(cfg, chunk, questions).map { result -> answersOf(result, questions.keys, cfg) }
        }
    }

    override suspend fun ping(): String {
        val cfg = config()
        if (cfg.baseUrl.isBlank()) throw LlmException("Decision service is not configured")
        val question = DecisionQuestion("Is this a test?", mapOf("yes" to "yes, a test", "no" to "no"))
        val result = batch(cfg, listOf("Test connection from ConvoScribe."), mapOf("test" to question)).single()
        answersOf(result, setOf("test"), cfg)
        val routing = result["routing"] as? JsonObject
        return (routing?.get("model") as? JsonPrimitive)?.contentOrNull.orEmpty().ifBlank { cfg.model }
    }

    /** One batch call: the `results` it returns, one object per state. */
    private suspend fun batch(
        cfg: DecisionConfig,
        states: List<String>,
        questions: Map<String, DecisionQuestion>
    ): List<JsonObject> {
        val url = cfg.baseUrl.trimEnd('/') + BATCH_PATH
        val body = buildJsonObject {
            put("states", buildJsonArray { states.forEach { add(JsonPrimitive(it)) } })
            put(
                "questions",
                buildJsonObject {
                    for ((id, q) in questions) {
                        put(
                            id,
                            buildJsonObject {
                                put("type", "choice")
                                put("instructions", q.instructions)
                                put(
                                    "criteria",
                                    buildJsonObject { q.options.forEach { (label, text) -> put(label, text) } }
                                )
                            }
                        )
                    }
                }
            )
            if (cfg.model.isNotBlank()) put("model", cfg.model)
        }
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .apply { if (cfg.apiKey.isNotBlank()) header("Authorization", "Bearer ${cfg.apiKey}") }
            .post(body.toString().toRequestBody(JSON))
            .build()
        val text = execute(http.newCall(request), url)
        val results = try {
            (Json.parseToJsonElement(text) as? JsonObject)?.get("results") as? JsonArray
        } catch (e: IllegalArgumentException) {
            null
        }
        val objects = results?.mapNotNull { it as? JsonObject }
        if (objects == null || objects.size != states.size) throw unexpected(url, text)
        return objects
    }

    private fun answersOf(result: JsonObject, ids: Set<String>, cfg: DecisionConfig): Map<String, DecisionAnswer> {
        val usage = result["usage"] as? JsonObject
        if ((usage?.get("truncated") as? JsonPrimitive)?.contentOrNull == "true") {
            // Lines are kept short enough that this should not happen; say so if it does.
            Log.w(
                TAG,
                "decision: state cut by the service, dropped=${usage["state_tokens_dropped"]} model=${cfg.model}"
            )
        }
        val answers = result["answers"] as? JsonObject
        return ids.associateWith { id ->
            val answer = answers?.get(id) as? JsonObject
            val choice = (answer?.get("choice") as? JsonPrimitive)?.contentOrNull
            val probabilities = (answer?.get("probabilities") as? JsonObject)
                ?.mapNotNull { (label, p) -> (p as? JsonPrimitive)?.doubleOrNull?.let { label to it } }
                ?.toMap()
            if (choice == null || probabilities == null) {
                throw LlmException("Unexpected answer from the decision service: no answer to $id")
            }
            DecisionAnswer(choice, probabilities)
        }
    }

    private suspend fun execute(call: Call, url: String): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A cancelled call fails too; the coroutine is already cancelled then and ignores this.
                    cont.resumeWithException(
                        LlmException("Network error calling $url: ${describeFailure(e)}", cause = e)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { resp ->
                        runCatching {
                            val text = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) throw httpError(resp.code, url, text)
                            text
                        }
                    }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { e ->
                            cont.resumeWithException(
                                e as? LlmException
                                    ?: LlmException("Network error calling $url: ${describeFailure(e)}", cause = e)
                            )
                        }
                    )
                }
            }
        )
    }

    private fun httpError(code: Int, url: String, text: String): LlmException {
        val detail = text.take(ERROR_BODY_CHARS)
        val hint = if (code == 404) " (the decision service needs laya-serve 0.4.22 or later)" else ""
        return LlmException("HTTP $code from $url: $detail$hint", httpCode = code)
    }

    private fun unexpected(url: String, text: String) =
        LlmException("Unexpected answer from $url: ${text.take(ERROR_BODY_CHARS)}")

    companion object {
        /** laya-serve's batch endpoint, under the decision service's base URL. */
        const val BATCH_PATH = "/v1/systemone/batch"

        /** States per batch call: laya-serve takes at most 64. */
        const val MAX_STATES = 64

        private const val ERROR_BODY_CHARS = 300
        private val JSON = "application/json".toMediaType()
    }
}
