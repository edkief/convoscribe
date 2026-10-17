package com.convoscribe.audio

import android.net.Uri
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Turns a session's WAV into an M4A the user can share or save.
 *
 * Encoding happens on demand into [exportDir] (the app cache, which
 * the system may clear), as `<sessionId>/<title>.m4a`. An existing
 * export that is newer than the WAV is reused, so sharing twice
 * encodes once. Encoding goes to a `.part` file that is renamed when
 * complete, so a cancelled or failed run leaves nothing behind that
 * could be mistaken for a finished export.
 *
 * @param encode WAV → M4A, see [M4aEncoder.encode]; a parameter so
 *   tests need no codecs.
 * @param openOutput opens the stream behind a document [Uri] the user
 *   picked, or `null` if it cannot be opened.
 */
class AudioExporter(
    private val exportDir: File,
    private val encode: (File, File, (Float) -> Unit, () -> Unit) -> Unit = M4aEncoder::encode,
    private val openOutput: (Uri) -> OutputStream? = { null }
) {

    /**
     * The M4A for [wav], encoding it if there is no up-to-date export.
     * Runs on a background dispatcher; cancel the calling coroutine to
     * abort. [onProgress] gets `0f..1f`.
     *
     * @throws IOException if the export cannot be written.
     */
    suspend fun export(sessionId: Long, title: String, wav: File, onProgress: (Float) -> Unit): File =
        withContext(Dispatchers.IO) {
            val out = File(File(exportDir, sessionId.toString()), fileName(title))
            if (out.isFile && out.length() > 0 && out.lastModified() >= wav.lastModified()) {
                return@withContext out
            }
            out.parentFile?.deleteRecursively()
            if (out.parentFile?.mkdirs() != true) throw IOException("Cannot create ${out.parentFile}")
            val part = File(out.parentFile, out.name + ".part")
            val context = coroutineContext
            try {
                encode(wav, part, onProgress) { context.ensureActive() }
                if (!part.renameTo(out)) throw IOException("Cannot write ${out.name}")
                out
            } finally {
                part.delete()
            }
        }

    /** Encode (or reuse) the M4A for [wav] and write it to the document at [target]. */
    suspend fun saveTo(target: Uri, sessionId: Long, title: String, wav: File, onProgress: (Float) -> Unit) {
        val exported = export(sessionId, title, wav, onProgress)
        withContext(Dispatchers.IO) {
            val output = openOutput(target) ?: throw IOException("Cannot open the chosen location")
            output.use { sink -> exported.inputStream().use { it.copyTo(sink) } }
        }
    }

    companion object {
        private const val MAX_NAME = 60

        /**
         * A file name for [title]: characters that are awkward in file
         * names become `_`, and the result is capped; blank titles
         * become `recording`. Ends in `.m4a`.
         */
        fun fileName(title: String): String {
            val cleaned = title.trim()
                .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.', '_')
                .take(MAX_NAME)
                .trim(' ', '.', '_')
            return (cleaned.ifEmpty { "recording" }) + ".m4a"
        }
    }
}
