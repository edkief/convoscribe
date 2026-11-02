package com.convoscribe.sync

import com.convoscribe.llm.describeFailure
import com.convoscribe.settings.OfferedService
import com.convoscribe.settings.SERVER_LLM_PATH
import com.convoscribe.settings.ServerOffer
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/** The answer of [ServerConfigClient.fetch]. */
sealed interface ServerConfigResult {
    /** What the server provides; an older server without the endpoint provides nothing. */
    data class Offered(val offer: ServerOffer) : ServerConfigResult

    /** The server refused the sync key ([code] 401 or 403). */
    data class Refused(val code: Int) : ServerConfigResult

    /** The server was not reached, or answered something unexpected. */
    data class Failed(val message: String) : ServerConfigResult
}

/**
 * Asks the sync server what it provides (issue #94), at
 * `GET /api/sync/v1/config` with the sync key. The answer names a
 * model per service, or none; nothing in it says where to send
 * anything, so a server cannot point the sync key elsewhere. When the
 * server provides a language model without naming one, the first model
 * of its `/api/ai/v1/llm/models` is used.
 */
class ServerConfigClient(
    private val http: OkHttpClient,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun fetch(serverUrl: String, apiKey: String): ServerConfigResult = withContext(Dispatchers.IO) {
        val base = serverUrl.trim().trimEnd('/')
        val answer = try {
            get("$base/api/sync/v1/config", apiKey)
        } catch (e: IOException) {
            return@withContext ServerConfigResult.Failed("Network error: ${describeFailure(e)}")
        } catch (e: IllegalArgumentException) {
            return@withContext ServerConfigResult.Failed("Not a valid server address")
        }
        when (answer.code) {
            200 -> {}
            // A server from before issue #93 offers nothing.
            404 -> return@withContext ServerConfigResult.Offered(ServerOffer(fetchedAt = now()))
            401, 403 -> return@withContext ServerConfigResult.Refused(answer.code)
            else -> return@withContext ServerConfigResult.Failed("HTTP ${answer.code} from the sync server")
        }
        val body = try {
            json.decodeFromString<ConfigBody>(answer.text)
        } catch (e: SerializationException) {
            return@withContext ServerConfigResult.Failed("Unexpected answer from the sync server")
        } catch (e: IllegalArgumentException) {
            return@withContext ServerConfigResult.Failed("Unexpected answer from the sync server")
        }
        val llm = body.llm?.let { OfferedService(it.model ?: firstLlmModel(base, apiKey)) }
        val transcription = body.transcription?.let { OfferedService(it.model.orEmpty()) }
        ServerConfigResult.Offered(ServerOffer(llm = llm, transcription = transcription, fetchedAt = now()))
    }

    /** The server's first language model, or blank when it lists none or the list fails. */
    private fun firstLlmModel(base: String, apiKey: String): String = try {
        val answer = get("$base$SERVER_LLM_PATH/models", apiKey)
        if (answer.code != 200) {
            ""
        } else {
            Json.parseToJsonElement(answer.text).jsonObject["data"]?.jsonArray
                ?.firstNotNullOfOrNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }
                .orEmpty()
        }
    } catch (e: IOException) {
        ""
    } catch (e: IllegalArgumentException) {
        // Not JSON, or not an OpenAI-style model list.
        ""
    }

    private class Answer(val code: Int, val text: String)

    private fun get(url: String, apiKey: String): Answer {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .apply { if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey") }
            .get()
            .build()
        return http.newCall(request).execute().use { Answer(it.code, it.body?.string().orEmpty()) }
    }

    @Serializable
    private data class ServiceBody(val model: String? = null)

    @Serializable
    private data class ConfigBody(val llm: ServiceBody? = null, val transcription: ServiceBody? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
