package com.convoscribe.update

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.models.ModelDownloadException
import com.convoscribe.models.ModelDownloader
import com.convoscribe.models.ResumableFetch
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** The downloaded APK's sha256 is not the one the release published. */
class Sha256MismatchException(message: String) : Exception(message)

/** Indirection over [UpdateDownloader] so [UpdateManager] tests can use a fake. */
interface UpdateDownloads {
    suspend fun download(
        offer: UpdateOffer,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long, waiting: Boolean) -> Unit
    ): File

    fun discard(offer: UpdateOffer)

    /** Delete downloads for [installed] or older; `true` when something was deleted. */
    fun cleanUp(installed: SemVer?): Boolean
}

/**
 * Downloads an [UpdateOffer]'s APK into `<dir>/<version>/<apk name>`
 * (issue #15), with the model downloader's machinery (issue #10): the
 * body goes to a `.part` file through [ResumableFetch], so a dropped
 * connection or a killed process resumes where it stopped, and a
 * transient failure waits [retryDelaysMs] and for [awaitNetwork] before
 * it tries again.
 *
 * When the release has a `.sha256` asset, the APK's digest must match
 * its first field (`sha256sum` output); a mismatch deletes the APK and
 * throws [Sha256MismatchException]. Other failures throw
 * [ModelDownloadException] and keep the part.
 */
class UpdateDownloader(
    private val client: OkHttpClient,
    private val dir: File,
    private val awaitNetwork: suspend () -> Boolean = { true },
    private val retryDelaysMs: List<Long> = ModelDownloader.DEFAULT_RETRY_DELAYS_MS
) : UpdateDownloads {
    private val fetch = ResumableFetch(client)

    /** Where [offer]'s APK lands once downloaded and checked. */
    fun apkFile(offer: UpdateOffer): File = File(File(dir, offer.versionName), offer.apk.name)

    /**
     * Fetch [offer]'s APK and check its sha256; returns the APK.
     * [onProgress] gets the bytes on disk, the total and whether a
     * retry is waiting.
     */
    override suspend fun download(
        offer: UpdateOffer,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long, waiting: Boolean) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = apkFile(offer)
        val part = File(target.parentFile, target.name + ".part")
        val total = offer.apk.sizeBytes
        val expectedSha = offer.sha256?.let { asset -> withRetries(part, onWaiting = {}) { readSha256(asset) } }
        if (!target.exists()) {
            withRetries(part, onWaiting = { onProgress(part.length(), total, true) }) {
                fetch.fetch(offer.apk.url, part, total.takeIf { it > 0 }, offer.apk.name) { onDisk ->
                    onProgress(onDisk, total, false)
                }
            }
            if (total > 0 && part.length() != total) {
                val got = part.length()
                part.delete()
                throw ModelDownloadException("Size mismatch for ${offer.apk.name}: expected $total, got $got")
            }
            if (!part.renameTo(target)) throw ModelDownloadException("Could not rename ${part.name}")
        }
        if (expectedSha != null) {
            val actual = sha256Of(target)
            if (!actual.equals(expectedSha, ignoreCase = true)) {
                target.delete()
                throw Sha256MismatchException("sha256 of ${target.name} is $actual, the release says $expectedSha")
            }
        }
        Log.i(TAG, "update ${offer.versionName} downloaded (${target.length()} bytes)")
        target
    }

    /** Delete what a download of [offer] fetched (Cancel). */
    override fun discard(offer: UpdateOffer) {
        apkFile(offer).parentFile?.deleteRecursively()
    }

    /**
     * Delete downloads for [installed] or older (they are installed or
     * superseded) and anything that is not a version directory.
     */
    override fun cleanUp(installed: SemVer?): Boolean {
        val children = dir.listFiles() ?: return false
        var deleted = false
        for (child in children) {
            val version = SemVer.parse(child.name)
            if (version == null || installed == null || version <= installed) {
                child.deleteRecursively()
                deleted = true
            }
        }
        return deleted
    }

    /** `sha256sum` output: the hex digest, then the file name. */
    private suspend fun readSha256(asset: ReleaseAsset): String {
        val request = Request.Builder().url(asset.url).build()
        val call = client.newCall(request)
        val text = try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw ModelDownloadException(
                        "HTTP ${resp.code} for ${asset.name}",
                        transient = ResumableFetch.isTransient(resp.code)
                    )
                }
                resp.body?.string().orEmpty()
            }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw ModelDownloadException("Network error downloading ${asset.name}: ${e.message}", e, transient = true)
        }
        val digest = text.trim().substringBefore(' ').substringBefore('\t').trim()
        if (!SHA256_HEX.matches(digest)) throw ModelDownloadException("${asset.name} holds no sha256")
        return digest
    }

    /** [ModelDownloader]'s retry rule: an attempt that grew [part] resets the budget. */
    private suspend fun <T> withRetries(part: File, onWaiting: suspend () -> Unit, block: suspend () -> T): T {
        var retries = 0
        while (true) {
            val before = part.length()
            try {
                return block()
            } catch (e: ModelDownloadException) {
                if (!e.transient) throw e
                if (part.length() > before) retries = 0
                if (retries >= retryDelaysMs.size) throw e
                Log.w(TAG, "update download failed, retry ${retries + 1}: ${e.message}")
                onWaiting()
                delay(retryDelaysMs[retries])
                retries++
                if (!awaitNetwork()) throw e
            }
        }
    }

    companion object {
        private val SHA256_HEX = Regex("^[0-9A-Fa-f]{64}$")

        fun sha256Of(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(ResumableFetch.BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
