package com.convoscribe.sync

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SessionManifest
import com.convoscribe.data.SessionManifests
import java.io.File
import java.io.IOException
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.SerializationException

/**
 * The manifest of each session as it last synced (issue #90), gzipped, one
 * file per session under [dir], for [com.convoscribe.data.SessionMerge] to
 * tell what changed on each side. The device keeps it because the server
 * keeps no history: clean-up removes old manifests after 7 days.
 *
 * A file is named after the hash in [com.convoscribe.data.SyncStateEntity.baseHash],
 * so one left by a crash is never taken for the base. A copy that can't be
 * written or read only makes the next merge go without a base, so neither
 * fails sync.
 */
class SyncBase(private val dir: File) {
    fun has(syncId: String, hash: String): Boolean = file(syncId, hash).isFile

    /** Store [manifest] as the copy hashing to [hash]. Call [keepOnly] once the state names it. */
    fun write(syncId: String, hash: String, manifest: SessionManifest) {
        val target = file(syncId, hash)
        val staged = File(dir, "${target.name}.tmp")
        try {
            dir.mkdirs()
            GZIPOutputStream(staged.outputStream()).bufferedWriter().use { it.write(SessionManifests.encode(manifest)) }
            if (!staged.renameTo(target)) throw IOException("Could not move ${staged.name}")
        } catch (e: IOException) {
            Log.w(TAG, "sync: could not keep the base of $syncId", e)
            staged.delete()
        }
    }

    /** The copy hashing to [hash], or `null` when there is none that reads. */
    fun read(syncId: String, hash: String): SessionManifest? {
        val file = file(syncId, hash)
        if (!file.isFile) return null
        return try {
            SessionManifests.decode(file.inputStream().use { GZIPInputStream(it).bufferedReader().readText() })
        } catch (e: IOException) {
            Log.w(TAG, "sync: could not read the base of $syncId", e)
            null
        } catch (e: SerializationException) {
            Log.w(TAG, "sync: could not read the base of $syncId", e)
            null
        }
    }

    /** Remove the session's other copies. */
    fun keepOnly(syncId: String, hash: String) {
        val keep = file(syncId, hash).name
        files().filter { syncIdOf(it) == syncId && it.name != keep }.forEach { it.delete() }
    }

    /** Remove the copies of every session not in [syncIds]. */
    fun retain(syncIds: Set<String>) {
        files().filter { syncIdOf(it) !in syncIds }.forEach { it.delete() }
    }

    private fun files(): List<File> = dir.listFiles()?.toList().orEmpty()

    private fun file(syncId: String, hash: String) = File(dir, "$syncId-$hash$SUFFIX")

    private fun syncIdOf(file: File) = file.name.substringBeforeLast('-')

    private companion object {
        const val SUFFIX = ".json.gz"
    }
}
