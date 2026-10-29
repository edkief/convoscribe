package com.convoscribe.sync

import com.convoscribe.data.FileHashEntity
import com.convoscribe.data.SessionManifest
import com.convoscribe.data.SessionManifests
import com.convoscribe.data.SyncDao
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [SessionManifest.FileRef]s of recordings, hashed once per size and
 * modification time (issue #68). Every sync run builds every session's
 * manifest, and hashing each WAV again each time would read hundreds
 * of megabytes for nothing.
 */
class FileHashCache(private val dao: SyncDao) {
    /** [file]'s reference, or `null` when it does not exist. */
    suspend fun ref(file: File): SessionManifest.FileRef? {
        if (!file.isFile) return null
        val size = file.length()
        val modified = file.lastModified()
        val cached = dao.fileHash(file.path)
        if (cached != null && cached.size == size && cached.lastModified == modified) {
            return SessionManifest.FileRef(cached.sha256, size)
        }
        val ref = withContext(Dispatchers.IO) { SessionManifests.fileRef(file) } ?: return null
        // A file that changed while it was read is hashed again next time.
        if (file.length() != size || file.lastModified() != modified) return ref
        val uploadId = cached?.uploadId?.takeIf { cached.sha256 == ref.sha256 }
        dao.upsertFileHash(FileHashEntity(file.path, size, modified, ref.sha256, uploadId))
        return ref
    }
}
