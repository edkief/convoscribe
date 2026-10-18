package com.convoscribe.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads one URL into a part file and resumes it after an
 * interruption (issue #10). Shared by the model downloader and meant
 * for any other large download (the in-app update, #15).
 *
 * The part file is kept on an IO error and on cancellation, so the
 * next [fetch] picks up where this one stopped; the caller deletes it
 * when it should not be resumed (Cancel, a size mismatch).
 */
class ResumableFetch(private val client: OkHttpClient) {

    /**
     * Fetch [url] into [part] until it holds the whole body.
     *
     * - A part longer than [expectedSize] is discarded first; one of
     *   exactly [expectedSize] bytes is complete and makes no request.
     * - A non-empty part sends `Range: bytes=<length>-`. A `206` whose
     *   `Content-Range` starts at that offset is appended; a `200`
     *   (the server ignored the range) truncates the part and writes
     *   the body from the start.
     * - [onBytes] gets the bytes on disk in [part] (not an increment)
     *   every [TICK_BYTES] and once at the end.
     *
     * Throws [ModelDownloadException] naming [label]. Network errors,
     * HTTP 5xx, 408 and 429 are [ModelDownloadException.transient];
     * other HTTP errors are not. The caller checks the final size.
     */
    @OptIn(InternalCoroutinesApi::class)
    suspend fun fetch(url: String, part: File, expectedSize: Long?, label: String, onBytes: suspend (Long) -> Unit) {
        part.parentFile?.mkdirs()
        if (expectedSize != null && part.length() > expectedSize) part.delete()
        val offset = part.length()
        if (expectedSize != null && offset == expectedSize && offset > 0) {
            onBytes(offset)
            return
        }
        val request = Request.Builder().url(url).apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = client.newCall(request)
        // Cancelling the coroutine aborts a read blocked on a stalled
        // socket at once, instead of after the read timeout. The handler
        // must run on cancelling: completion waits for this very read.
        val cancelOnCancel = currentCoroutineContext()[Job]
            ?.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { call.cancel() }
        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw ModelDownloadException("Network error downloading $label: ${e.message}", e, transient = true)
            }
            response.use { resp ->
                val append = when {
                    offset > 0 && resp.code == 206 && startsAt(resp.header("Content-Range"), offset) -> true
                    resp.code == 206 -> {
                        // A range we did not ask for: start the file over.
                        part.delete()
                        throw ModelDownloadException(
                            "Unexpected range for $label: ${resp.header("Content-Range")}",
                            transient = true
                        )
                    }
                    resp.code == 416 -> {
                        // The part no longer matches the file on the server.
                        part.delete()
                        throw ModelDownloadException("HTTP 416 for $label", transient = true)
                    }
                    resp.isSuccessful -> false
                    else -> throw ModelDownloadException(
                        "HTTP ${resp.code} for $label",
                        transient = isTransient(resp.code)
                    )
                }
                val body = resp.body ?: throw ModelDownloadException("Empty body for $label", transient = true)
                var onDisk = if (append) offset else 0L
                try {
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var sinceLastTick = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read == -1) break
                                out.write(buffer, 0, read)
                                onDisk += read
                                sinceLastTick += read
                                if (sinceLastTick >= TICK_BYTES) {
                                    onBytes(onDisk)
                                    sinceLastTick = 0L
                                }
                            }
                        }
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw ModelDownloadException("IO error writing $label: ${e.message}", e, transient = true)
                }
                onBytes(onDisk)
            }
        } finally {
            cancelOnCancel?.dispose()
        }
    }

    /** `Content-Range: bytes <offset>-<end>/<total>` starts at [offset]. */
    private fun startsAt(contentRange: String?, offset: Long): Boolean =
        contentRange?.trim()?.removePrefix("bytes")?.trim()?.substringBefore('-') == offset.toString()

    companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val TICK_BYTES = 256L * 1024

        /** Worth retrying: server errors, request timeout, rate limiting. */
        fun isTransient(httpCode: Int): Boolean = httpCode >= 500 || httpCode == 408 || httpCode == 429
    }
}
