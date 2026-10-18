package com.convoscribe.models

import android.util.Log
import com.convoscribe.TAG
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * Reason a [ModelDownloader] stopped, surfaced verbatim to the UI.
 * Keeps [ModelDownloader] free of an Android dependency while
 * distinguishing network/IO failures (HTTP 5xx, DNS, timeout, partial
 * body) from programmer errors (illegal URL, null body).
 *
 * [transient] marks a failure worth retrying once the network is back
 * (a timeout, a reset, HTTP 5xx/408/429); a permanent one (HTTP 404, a
 * size mismatch, a missing archive entry) fails at once.
 */
class ModelDownloadException(
    message: String,
    cause: Throwable? = null,
    val transient: Boolean = false
) : Exception(message, cause)

/**
 * Per-file progress and terminal event for [ModelDownloader.download].
 * `bytesDone` is cumulative across every file in the call; for a file
 * already installed with the exact size it counts as done at the
 * start. The UI subscribes to this flow to drive a progress bar.
 *
 * [waiting] is `true` while a transient failure waits for its retry
 * (backoff, then the network coming back). [unpacking] is the share
 * `0f..1f` of a downloaded archive extracted so far, `null` outside
 * extraction; `bytesDone` already counts the whole archive then.
 */
sealed interface DownloadEvent {
    data class Progress(
        val bytesDone: Long,
        val bytesTotal: Long,
        val currentFile: String,
        val waiting: Boolean = false,
        val unpacking: Float? = null
    ) : DownloadEvent

    data object Completed : DownloadEvent
}

/**
 * Indirection over [ModelDownloader] so the ViewModel layer can be
 * unit-tested with a fake without reaching for OkHttp's MockWebServer.
 * The production implementation is [ModelDownloader]; the interface
 * stays tiny so test fakes are short.
 */
interface ModelDownloads {
    fun download(bundles: List<ModelBundle>): Flow<DownloadEvent>

    /** Delete what an interrupted [download] of [bundles] kept to resume from. */
    fun discardPartial(bundles: List<ModelBundle>)
}

/**
 * HTTPS downloader for the model's [ModelBundle]s. Each call to
 * [download] resolves every file in every bundle.
 *
 * - Plain bundles: skip files already on disk at the exact size,
 *   otherwise fetch the body into `<target>.part` with [ResumableFetch]
 *   (resuming a part an earlier attempt left), emit
 *   [DownloadEvent.Progress] at most every 256 KiB plus once at the end
 *   of each file, verify the byte count, then rename the part to the
 *   target atomically.
 * - Archive bundles ([ModelArchive] is set): [ModelArchive.url] is
 *   fetched the same way into [ModelStore.archivePartFor], then
 *   extracted through bzip2 + tar decoders. Only entries whose path
 *   matches a listed [ModelFile.fileName] are written (each verified
 *   by size); all other entries are skipped. The archive is deleted
 *   once extracted. Already-installed archive bundles are skipped
 *   entirely — no HTTP request is made. Before the fetch, [freeBytes]
 *   must cover the rest of the archive plus its extracted files.
 *
 * A transient failure ([ModelDownloadException.transient]) keeps the
 * part, waits [retryDelaysMs] and then for [awaitNetwork], and resumes;
 * an attempt that added bytes resets the retry budget. A wrong byte
 * count, a missing archive entry, or any other hard failure removes
 * the partial file (or archive contents) so the next run starts over.
 */
class ModelDownloader(
    client: OkHttpClient,
    private val store: ModelStore,
    private val awaitNetwork: suspend () -> Boolean = { true },
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val freeBytes: () -> Long = store::usableSpace
) : ModelDownloads {

    private val fetch = ResumableFetch(client)

    /**
     * Fetch every file in [bundles], in input order, then emit
     * [DownloadEvent.Completed]. Already-installed files count toward
     * [DownloadEvent.Progress.bytesDone] but no HTTP request is made.
     * Cancellation propagates via [kotlinx.coroutines.ensureActive] in
     * the copy loops, so [Flow.collect] on a cancelled scope tears down
     * the in-flight request; the part files are kept for a later run.
     */
    override fun download(bundles: List<ModelBundle>): Flow<DownloadEvent> = flow {
        // The downloader's denominator matches what it actually pulls
        // over the network: `archive.sizeBytes` for archive bundles
        // (compressed body), `totalBytes` (sum of declared files) for
        // plain bundles.
        val bytesTotal = bundles.sumOf { bundle -> bundle.downloadBytes }
        var bytesDone = 0L
        for (bundle in bundles) {
            val archive = bundle.archive
            if (archive != null) {
                if (!store.isInstalled(bundle)) {
                    downloadArchive(bundle, archive, bytesDone, bytesTotal)
                }
                // Skip the network entirely for an installed bundle; it
                // is done before we start.
                bytesDone += archive.sizeBytes
                emit(DownloadEvent.Progress(bytesDone, bytesTotal, bundle.id))
                continue
            }
            for (file in bundle.files) {
                val target = store.fileFor(bundle, file.fileName)
                if (!(target.exists() && target.length() == file.sizeBytes)) {
                    downloadOne(file, target, bytesDone, bytesTotal)
                }
                // Already on disk at the right size counts too, so
                // `bytesDone == bytesTotal` after a no-op run.
                bytesDone += file.sizeBytes
                emit(DownloadEvent.Progress(bytesDone, bytesTotal, file.fileName))
            }
        }
        Log.i(TAG, "model download completed bytesDone=$bytesTotal bundles=${bundles.size}")
        emit(DownloadEvent.Completed)
    }.flowOn(Dispatchers.IO)

    override fun discardPartial(bundles: List<ModelBundle>) {
        for (bundle in bundles) store.discardPartial(bundle)
    }

    /**
     * Fetches [file.url] into `<target>.part`, verifies the byte count,
     * then renames it into place. Progress is [startBytesDone] plus the
     * part's bytes on disk.
     */
    private suspend fun FlowCollector<DownloadEvent>.downloadOne(
        file: ModelFile,
        target: File,
        startBytesDone: Long,
        bytesTotal: Long
    ) {
        val partFile = File(target.parentFile, target.name + ".part")
        withRetries(
            part = partFile,
            label = file.fileName,
            waitingEvent = {
                DownloadEvent.Progress(startBytesDone + partFile.length(), bytesTotal, file.fileName, waiting = true)
            }
        ) {
            fetch.fetch(file.url, partFile, file.sizeBytes, file.fileName) { onDisk ->
                emit(DownloadEvent.Progress(startBytesDone + onDisk, bytesTotal, file.fileName))
            }
        }
        if (partFile.length() != file.sizeBytes) {
            val got = partFile.length()
            partFile.delete()
            throw ModelDownloadException(
                "Size mismatch for ${file.fileName}: " +
                    "expected ${file.sizeBytes}, got $got"
            )
        }
        if (!partFile.renameTo(target)) {
            throw ModelDownloadException(
                "Could not rename ${partFile.name} to ${target.name}"
            )
        }
    }

    /**
     * Fetches [archive.url] into [ModelStore.archivePartFor] (resuming),
     * extracts the listed files, then deletes the archive. Progress
     * counts compressed bytes while fetching, then reports
     * [DownloadEvent.Progress.unpacking]. Already-installed archive
     * bundles must not be passed here — the caller short-circuits.
     */
    private suspend fun FlowCollector<DownloadEvent>.downloadArchive(
        bundle: ModelBundle,
        archive: ModelArchive,
        startBytesDone: Long,
        bytesTotal: Long
    ) {
        val archiveFile = store.archivePartFor(bundle)
        // The archive and its extracted files are on disk together
        // until extraction ends.
        val needed = (archive.sizeBytes - archiveFile.length()).coerceAtLeast(0L) + bundle.totalBytes
        val free = freeBytes()
        if (free < needed) {
            throw ModelDownloadException(
                "Not enough free space for ${bundle.displayName}: " +
                    "need ${needed / 1_000_000} MB, ${free / 1_000_000} MB free"
            )
        }
        withRetries(
            part = archiveFile,
            label = archive.rootDir,
            waitingEvent = {
                DownloadEvent.Progress(startBytesDone + archiveFile.length(), bytesTotal, bundle.id, waiting = true)
            }
        ) {
            fetch.fetch(archive.url, archiveFile, archive.sizeBytes, archive.rootDir) { onDisk ->
                emit(DownloadEvent.Progress(startBytesDone + onDisk, bytesTotal, bundle.id))
            }
        }
        if (archiveFile.length() != archive.sizeBytes) {
            val got = archiveFile.length()
            archiveFile.delete()
            throw ModelDownloadException(
                "Size mismatch for ${archive.rootDir}: " +
                    "expected ${archive.sizeBytes}, got $got"
            )
        }
        val downloaded = startBytesDone + archive.sizeBytes
        extractArchive(bundle, archive, archiveFile) { fraction, currentFile ->
            emit(DownloadEvent.Progress(downloaded, bytesTotal, currentFile, unpacking = fraction))
        }
        archiveFile.delete()
    }

    /**
     * Runs [block] until it succeeds. A transient
     * [ModelDownloadException] emits [waitingEvent], waits the next
     * [retryDelaysMs] entry and for [awaitNetwork], then tries again
     * (resuming [part]); after the last delay, or when the network does
     * not come back, the failure is rethrown. An attempt that grew
     * [part] starts the delays over, so a long download survives many
     * short outages.
     */
    private suspend fun FlowCollector<DownloadEvent>.withRetries(
        part: File,
        label: String,
        waitingEvent: () -> DownloadEvent,
        block: suspend () -> Unit
    ) {
        var retries = 0
        while (true) {
            val before = part.length()
            try {
                block()
                return
            } catch (e: ModelDownloadException) {
                if (!e.transient) throw e
                if (part.length() > before) retries = 0
                if (retries >= retryDelaysMs.size) throw e
                Log.w(TAG, "model download of $label failed, retry ${retries + 1}: ${e.message}")
                emit(waitingEvent())
                delay(retryDelaysMs[retries])
                retries++
                if (!awaitNetwork()) throw e
            }
        }
    }

    /**
     * Walks the bzip2 + tar [archiveFile], writes only the entries
     * listed in [bundle.files] (matching by fileName with a leading
     * `./` and `rootDir + "/"` stripped first) to `<target>.part`,
     * verifies each by size, then renames. Throws
     * [ModelDownloadException] for size mismatches, missing entries
     * and a corrupt archive, after deleting the archive (fetching it
     * again is the only fix). Cancellation keeps the archive.
     *
     * [onTick] receives the share of the archive read so far every
     * [TICK_BYTES], including while a large entry is being extracted.
     */
    private suspend fun extractArchive(
        bundle: ModelBundle,
        archive: ModelArchive,
        archiveFile: File,
        onTick: suspend (fraction: Float, currentFile: String) -> Unit
    ) {
        val listedByName = bundle.files.associateBy { it.fileName }
        val writtenFiles = mutableSetOf<String>()
        val partFiles = mutableListOf<File>()
        val archiveSize = archiveFile.length().coerceAtLeast(1L)

        fun fail(message: String, cause: Throwable? = null): Nothing {
            for (p in partFiles) p.delete()
            archiveFile.delete()
            throw ModelDownloadException(message, cause)
        }

        // A counting stream on top of the file so progress ticks are
        // reported in compressed-bytes terms.
        var compressedRead = 0L
        var lastTicked = 0L

        // Report the share read so far once at least TICK_BYTES have
        // been read since the previous report.
        suspend fun tickIfDue(currentFile: String) {
            if (compressedRead - lastTicked >= TICK_BYTES) {
                onTick((compressedRead.toFloat() / archiveSize).coerceAtMost(1f), currentFile)
                lastTicked = compressedRead
            }
        }

        try {
            val counting = object : FilterInputStream(FileInputStream(archiveFile)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) compressedRead += n
                    return n
                }

                override fun read(): Int {
                    val b = super.read()
                    if (b >= 0) compressedRead += 1
                    return b
                }
            }
            TarArchiveInputStream(
                BufferedInputStream(
                    BZip2CompressorInputStream(counting),
                    BUFFER_SIZE
                )
            ).use { tar ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry: TarArchiveEntry = tar.nextEntry ?: break
                    val stripped = stripArchivePrefix(entry.name, archive.rootDir)
                    if (stripped == null || entry.isDirectory) {
                        // Skipped entry; still report the compressed
                        // bytes the bzip2 layer consumed for it.
                        tickIfDue(bundle.id)
                        continue
                    }
                    val listed = listedByName[stripped]
                    if (listed == null) {
                        tickIfDue(bundle.id)
                        continue
                    }
                    val target = store.fileFor(bundle, stripped)
                    val partFile = File(target.parentFile, target.name + ".part")
                    target.parentFile?.mkdirs()
                    partFiles.add(partFile)
                    val expectedSize = listed.sizeBytes
                    val entryBytes = writeTarEntry(tar, partFile) { tickIfDue(stripped) }
                    if (entryBytes != expectedSize) {
                        fail(
                            "Size mismatch for ${listed.fileName}: " +
                                "expected $expectedSize, got $entryBytes"
                        )
                    }
                    if (!partFile.renameTo(target)) {
                        fail("Could not rename ${partFile.name} to ${target.name}")
                    }
                    writtenFiles.add(stripped)
                    tickIfDue(stripped)
                }
            }
        } catch (e: IOException) {
            fail("IO error extracting ${archive.rootDir}: ${e.message}", e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            for (p in partFiles) p.delete()
            throw e
        }

        // The contract requires every listed file to be present
        // in the archive. A short archive yields a hard error.
        for (file in bundle.files) {
            if (!writtenFiles.contains(file.fileName)) {
                fail("Archive is missing ${file.fileName}")
            }
        }
        onTick(1f, bundle.id)
    }

    /**
     * Reads the rest of [tar]'s current entry into [part] and returns
     * the byte count written. [onChunk] runs after every buffer so
     * progress keeps moving through a multi-hundred-MB entry, and the
     * loop checks for cancellation for the same reason.
     */
    private suspend fun writeTarEntry(tar: TarArchiveInputStream, part: File, onChunk: suspend () -> Unit): Long {
        var bytes = 0L
        FileOutputStream(part).use { out ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = tar.read(buffer)
                if (read == -1) break
                out.write(buffer, 0, read)
                bytes += read
                onChunk()
            }
        }
        return bytes
    }

    /**
     * Strip a leading `./` and a leading `<rootDir>/` from a tar
     * entry name. Returns the relative file name if it matches the
     * expected root, `null` otherwise. Directory markers (trailing
     * `/`) are also rejected.
     */
    private fun stripArchivePrefix(rawName: String, rootDir: String): String? {
        var name = rawName
        if (name.startsWith("./")) name = name.substring(2)
        if (name.endsWith("/")) return null
        val prefix = "$rootDir/"
        if (name.startsWith(prefix)) {
            name = name.substring(prefix.length)
        } else if (name == rootDir) {
            return null
        }
        return name
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024
        private const val TICK_BYTES = 256L * 1024

        /**
         * Waits before each retry of a transient failure: about a
         * minute of backoff in all, plus however long [awaitNetwork]
         * waits for the network after each one.
         */
        val DEFAULT_RETRY_DELAYS_MS = listOf(2_000L, 4_000L, 8_000L, 16_000L, 32_000L)
    }
}
