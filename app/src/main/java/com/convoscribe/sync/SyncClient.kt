package com.convoscribe.sync

import com.convoscribe.llm.describeFailure
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.zip.GZIPOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * A failed call to the sync server (issue #68). [code] is the HTTP
 * status, or `null` when the server was not reached ([offline]).
 */
class SyncException(message: String, val code: Int? = null, cause: Throwable? = null) : Exception(message, cause) {
    /** The network failed, so the rest of the run would fail too. */
    val offline: Boolean get() = code == null
}

/** The server's index entry for a document, as its routes return it. */
@Serializable
data class DocumentMeta(
    val kind: String,
    val syncId: String,
    val revision: Long,
    val seq: Long,
    val durationMs: Long = 0,
    val changedAt: Long = 0,
    val manifestHash: String? = null,
    val deleted: Boolean = false
)

/** One page of the change feed (#69): documents written after a cursor, oldest first. */
@Serializable
data class ChangesPage(val changes: List<DocumentMeta>, val next: Long, val more: Boolean)

/** What fetching a document's current revision found (#69). */
sealed interface Fetched {
    /** The manifest bytes of [revision], last changed at [changedAt]. */
    class Found(val manifest: ByteArray, val revision: Long, val changedAt: Long) : Fetched

    /** The document was deleted, at [revision]. */
    data class Deleted(val revision: Long) : Fetched

    /** The server does not know it. */
    data object Missing : Fetched
}

/** What a push of a manifest or a delete came to. */
sealed interface PushResult {
    data class Accepted(val meta: DocumentMeta) : PushResult

    /** The server's version, from another device, won the conflict rule (#69). */
    data class Conflict(val current: DocumentMeta?) : PushResult

    /** The manifest names recordings the server does not have yet. */
    data class MissingBlobs(val missing: List<String>) : PushResult

    /**
     * Deleting a document the server never had, or no longer has; for a
     * push, a kind the server does not know (one older than #70).
     */
    data object NotFound : PushResult
}

/**
 * Talks to the sync server's `/api/sync/v1` routes (issue #68) with
 * `Authorization: Bearer` [apiKey], as [com.convoscribe.asr.server.ServerTranscriber]
 * does for the transcription server. Unexpected answers and network
 * failures throw [SyncException].
 */
class SyncClient(
    private val http: OkHttpClient,
    serverUrl: String,
    private val apiKey: String
) {
    private val base = serverUrl.trim().trimEnd('/') + "/api/sync/v1"

    /** Whether the server already stores the blob [sha256]. */
    suspend fun hasBlob(sha256: String): Boolean {
        val response = call(request("blobs/$sha256").head())
        return when (response.code) {
            200 -> true
            404 -> false
            else -> throw unexpected(response)
        }
    }

    /** Start uploading [sha256]: its upload id, or `null` when the server already has it. */
    suspend fun startUpload(sha256: String): String? {
        val response = call(request("blobs/$sha256/uploads").post(EMPTY))
        return when (response.code) {
            200 -> null
            201 -> json.decodeFromString(UploadStarted.serializer(), response.text).id
            else -> throw unexpected(response)
        }
    }

    /** The part numbers the server has for upload [id], or `null` when it no longer knows it. */
    suspend fun receivedParts(sha256: String, id: String): Set<Int>? {
        val response = call(request("blobs/$sha256/uploads/$id").get())
        return when (response.code) {
            200 -> json.decodeFromString(PartList.serializer(), response.text).parts.map { it.n }.toSet()
            404 -> null
            else -> throw unexpected(response)
        }
    }

    /** Send bytes [offset] to `offset + length` of [file] as part [n]. */
    suspend fun putPart(sha256: String, id: String, n: Int, file: File, offset: Long, length: Long) {
        val response = call(request("blobs/$sha256/uploads/$id/parts/$n").put(FileRangeBody(file, offset, length)))
        if (response.code != 204 && response.code != 200) throw unexpected(response)
    }

    /** Finish upload [id]; the server checks the bytes hash to [sha256]. */
    suspend fun completeUpload(sha256: String, id: String) {
        val response = call(request("blobs/$sha256/uploads/$id/complete").post(EMPTY))
        if (response.code != 200) throw unexpected(response)
    }

    /** Push [manifest] as the revision after [baseRevision] (0 for a new document), gzipped. */
    suspend fun putDocument(
        kind: String,
        syncId: String,
        manifest: ByteArray,
        manifestHash: String,
        baseRevision: Long,
        durationMs: Long,
        changedAt: Long
    ): PushResult {
        val body = gzip(manifest).toRequestBody(JSON_TYPE)
        val response = call(
            request("documents/$kind/$syncId").put(body)
                .header("Content-Encoding", "gzip")
                .header("Sync-Base-Revision", baseRevision.toString())
                .header("Sync-Manifest-Hash", manifestHash)
                .header("Sync-Duration-Ms", durationMs.coerceAtLeast(0).toString())
                .header("Sync-Changed-At", changedAt.coerceAtLeast(0).toString())
        )
        return when (response.code) {
            200 -> PushResult.Accepted(json.decodeFromString(DocumentMeta.serializer(), response.text))
            404 -> PushResult.NotFound
            409 -> PushResult.Conflict(json.decodeFromString(ErrorBody.serializer(), response.text).current)
            422 -> PushResult.MissingBlobs(json.decodeFromString(ErrorBody.serializer(), response.text).missing)
            else -> throw unexpected(response)
        }
    }

    /**
     * Delete [syncId] on the server, which leaves a tombstone for other
     * devices. [durationMs] is the length of the deleted version, for
     * the conflict rule when [baseRevision] is no longer current (#69).
     */
    suspend fun deleteDocument(kind: String, syncId: String, baseRevision: Long, durationMs: Long?): PushResult {
        val response = call(
            request("documents/$kind/$syncId").delete()
                .header("Sync-Base-Revision", baseRevision.toString())
                .apply { if (durationMs != null) header("Sync-Duration-Ms", durationMs.coerceAtLeast(0).toString()) }
        )
        return when (response.code) {
            200 -> PushResult.Accepted(json.decodeFromString(DocumentMeta.serializer(), response.text))
            404 -> PushResult.NotFound
            409 -> PushResult.Conflict(json.decodeFromString(ErrorBody.serializer(), response.text).current)
            else -> throw unexpected(response)
        }
    }

    /** Documents written after cursor [since], at most [limit] (#69). */
    suspend fun changes(since: Long, limit: Int): ChangesPage {
        val response = call(request("changes?since=$since&limit=$limit").get())
        if (response.code != 200) throw unexpected(response)
        return json.decodeFromString(ChangesPage.serializer(), response.text)
    }

    /** The current revision of [syncId] (#69). */
    suspend fun getDocument(kind: String, syncId: String): Fetched {
        val response = call(request("documents/$kind/$syncId").get())
        return when (response.code) {
            200 -> Fetched.Found(
                manifest = response.text.toByteArray(Charsets.UTF_8),
                revision = response.headers["Sync-Revision"]?.toLongOrNull()
                    ?: throw SyncException("No Sync-Revision from the sync server", code = 502),
                changedAt = response.headers["Sync-Changed-At"]?.toLongOrNull() ?: 0L
            )
            410 -> Fetched.Deleted(json.decodeFromString(ErrorBody.serializer(), response.text).current?.revision ?: 0)
            404 -> Fetched.Missing
            else -> throw unexpected(response)
        }
    }

    /** Where blob [sha256] downloads from, with Range support (#69); send [authHeaders] with it. */
    fun blobUrl(sha256: String): String = "$base/blobs/$sha256"

    val authHeaders: Map<String, String>
        get() = if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")

    private fun request(path: String): Request.Builder = Request.Builder().url("$base/$path").apply {
        if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
    }

    private class Answer(val code: Int, val text: String, val headers: okhttp3.Headers = okhttp3.Headers.headersOf())

    /** Run [builder]'s request, cancelling it with the calling coroutine. */
    private suspend fun call(builder: Request.Builder): Answer {
        val request = builder.build()
        val call = http.newCall(request)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        cont.resumeWithException(
                            SyncException("Network error calling ${request.url}: ${describeFailure(e)}", cause = e)
                        )
                    }

                    override fun onResponse(call: Call, response: Response) {
                        runCatching { response.use { Answer(it.code, it.body?.string().orEmpty(), it.headers) } }
                            .fold(
                                onSuccess = { cont.resume(it) },
                                onFailure = { e ->
                                    cont.resumeWithException(
                                        SyncException(
                                            "Network error reading ${request.url}: ${describeFailure(e)}",
                                            cause = e
                                        )
                                    )
                                }
                            )
                    }
                }
            )
        }
    }

    private fun unexpected(answer: Answer): SyncException {
        val hint = when (answer.code) {
            401 -> " (check the sync API key)"
            413 -> " (the reverse proxy refused the size; allow at least 8 MB)"
            else -> ""
        }
        return SyncException("HTTP ${answer.code} from the sync server$hint: ${answer.text.take(300)}", answer.code)
    }

    @Serializable
    private data class UploadStarted(val id: String? = null, val exists: Boolean = false)

    @Serializable
    private data class Part(val n: Int, val size: Long)

    @Serializable
    private data class PartList(val parts: List<Part>)

    @Serializable
    private data class ErrorBody(
        val error: String,
        val current: DocumentMeta? = null,
        val missing: List<String> = emptyList()
    )

    /** Streams one range of a file, so a part is never held in memory whole. */
    private class FileRangeBody(private val file: File, private val offset: Long, private val length: Long) :
        RequestBody() {
        override fun contentType() = OCTET_TYPE

        override fun contentLength(): Long = length

        override fun writeTo(sink: BufferedSink) {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                val buffer = ByteArray(CHUNK_BYTES)
                var left = length
                while (left > 0) {
                    val read = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (read < 0) throw IOException("${file.name} ended before its part did")
                    sink.write(buffer, 0, read)
                    left -= read
                }
            }
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val EMPTY: RequestBody = ByteArray(0).toRequestBody(null)
        val JSON_TYPE = "application/json".toMediaType()
        val OCTET_TYPE = "application/octet-stream".toMediaType()
        const val CHUNK_BYTES = 64 * 1024

        fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { it.write(bytes) }
        }.toByteArray()
    }
}
