package com.convoscribe.asr.server

import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.llm.describeFailure
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source

/**
 * Transcription server configuration (issue #58), built from
 * [com.convoscribe.settings.SettingsRepository] at call time like
 * [com.convoscribe.llm.LlmConfig]. [baseUrl] is the OpenAI-style base,
 * typically ending in `/v1`; a blank one means no server is set up.
 * [model] may be blank: the server's first listed model is then used.
 */
data class ServerAsrConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
)

/**
 * Errors raised by [ServerTranscriber]. The message is what the user
 * is shown: for HTTP failures `HTTP <code> from <url>: ` and at most
 * 300 characters of the body, for network failures the URL and the
 * exception chain ([describeFailure]).
 */
class ServerAsrException(
    message: String,
    val httpCode: Int? = null,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Transcribes a stored recording on a self-hosted, OpenAI-compatible
 * transcription server (issue #58), such as NVIDIA Parakeet behind a
 * `POST /v1/audio/transcriptions` endpoint on a GPU.
 *
 * The WAV is uploaded whole as `multipart/form-data` with
 * `response_format=verbose_json` and word and segment timestamps, and
 * the reply is turned into lines by [ServerResponseParser]. Progress
 * follows the upload up to [UPLOAD_SHARE]; the rest of the bar is the
 * server's work, which reports nothing, so it stays there until the
 * reply arrives. Cancelling the calling coroutine cancels the call.
 *
 * [config] is read on every call, so a Settings change applies to the
 * next transcription without rebuilding the client.
 */
class ServerTranscriber(
    private val http: OkHttpClient,
    private val config: () -> ServerAsrConfig
) : FileRecognizer {

    /** Model of the last [transcribe], for [describe]. */
    @Volatile
    private var lastModel: String? = null

    override suspend fun transcribe(
        file: File,
        language: String,
        onProgress: (Float) -> Unit
    ): List<TranscriptEvent.Final> {
        val cfg = config()
        if (cfg.baseUrl.isBlank()) throw ServerAsrException("The transcription server is not set up")
        // verbose_json does not name the model; ask for the server's
        // first one so the session can say what transcribed it.
        val model = cfg.model.ifBlank { runCatching { listModels().firstOrNull() }.getOrNull().orEmpty() }
        val url = cfg.baseUrl.trimEnd('/') + "/audio/transcriptions"
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                file.name,
                ProgressFileBody(file, WAV_MEDIA_TYPE) { sent, total ->
                    onProgress(UPLOAD_SHARE * sent / total.coerceAtLeast(1L))
                }
            )
            .addFormDataPart("response_format", "verbose_json")
            .addFormDataPart("timestamp_granularities[]", "word")
            .addFormDataPart("timestamp_granularities[]", "segment")
            .addFormDataPart("language", language)
            .apply { if (model.isNotBlank()) addFormDataPart("model", model) }
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .authorize(cfg.apiKey)
            .post(body)
            .build()
        val text = execute(http.newCall(request), url)
        val finals = withContext(Dispatchers.Default) { ServerResponseParser.parse(text) }
        lastModel = model.ifBlank { null }
        onProgress(1f)
        return finals
    }

    override fun describe(): EngineInfo = EngineInfo(ENGINE, lastModel ?: config().model.ifBlank { "unknown" })

    /**
     * GET `/models` and return the `data[].id` list, for Settings →
     * Test. Throws [ServerAsrException] like [transcribe].
     */
    suspend fun listModels(): List<String> {
        val cfg = config()
        if (cfg.baseUrl.isBlank()) throw ServerAsrException("The transcription server is not set up")
        val url = cfg.baseUrl.trimEnd('/') + "/models"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .authorize(cfg.apiKey)
            .get()
            .build()
        val text = execute(http.newCall(request), url)
        return try {
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonArray.orEmpty().mapNotNull { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
            }
        } catch (e: IllegalArgumentException) {
            throw ServerAsrException(
                "Unexpected response from $url (not an OpenAI-style model list): ${text.take(200)}",
                cause = e
            )
        }
    }

    /**
     * Run [call] and return its body, cancelling it with the calling
     * coroutine. Non-2xx and I/O failures become [ServerAsrException].
     */
    private suspend fun execute(call: Call, url: String): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // A cancelled call fails too; the coroutine is
                    // already cancelled then and ignores this.
                    cont.resumeWithException(networkError(url, e))
                }

                override fun onResponse(call: Call, response: Response) {
                    val result = response.use { resp ->
                        runCatching {
                            val text = resp.body?.string().orEmpty()
                            if (!resp.isSuccessful) {
                                throw ServerAsrException("HTTP ${resp.code} from $url: ${text.take(300)}", resp.code)
                            }
                            text
                        }
                    }
                    result.fold(
                        onSuccess = { cont.resume(it) },
                        onFailure = { e ->
                            cont.resumeWithException(e as? ServerAsrException ?: networkError(url, e))
                        }
                    )
                }
            }
        )
    }

    private fun networkError(url: String, e: Throwable) =
        ServerAsrException("Network error calling $url: ${describeFailure(e)}", cause = e)

    private fun Request.Builder.authorize(apiKey: String): Request.Builder = apply {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    /** Streams [file], reporting bytes sent so far out of the total. */
    private class ProgressFileBody(
        private val file: File,
        private val type: MediaType,
        private val onSent: (sent: Long, total: Long) -> Unit
    ) : RequestBody() {
        override fun contentType(): MediaType = type

        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var sent = 0L
            file.source().use { source ->
                while (true) {
                    val read = source.read(sink.buffer, CHUNK_BYTES)
                    if (read == -1L) break
                    sink.emit()
                    sent += read
                    onSent(sent, total)
                }
            }
        }
    }

    companion object {
        /** [EngineInfo.engine] of a transcript made on the server. */
        const val ENGINE = "server"

        /** Share of the progress bar given to the upload. */
        const val UPLOAD_SHARE = 0.9f

        private const val CHUNK_BYTES = 64L * 1024
        private val WAV_MEDIA_TYPE = "audio/wav".toMediaType()
    }
}
