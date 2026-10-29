package com.convoscribe.sync

import com.convoscribe.data.SessionManifest
import com.convoscribe.data.SyncDao
import java.io.File

/**
 * Gets a recording onto the sync server (issue #68): nothing when the
 * server already has its hash, otherwise in parts of [partBytes],
 * resuming an upload an earlier run started and sending only the
 * parts the server lacks. The server checks the hash on completion.
 */
class BlobUploader(
    private val client: SyncClient,
    private val dao: SyncDao,
    private val partBytes: Long = PART_BYTES
) {
    /** Make sure the server stores [file], whose bytes are [ref]. */
    suspend fun ensure(ref: SessionManifest.FileRef, file: File) {
        if (client.hasBlob(ref.sha256)) return
        if (file.length() != ref.size) throw SyncException("${file.name} changed while syncing", code = CHANGED)

        val previous = dao.uploadId(ref.sha256)
        val received = previous?.let { client.receivedParts(ref.sha256, it) }
        val id = if (previous != null && received != null) {
            previous
        } else {
            client.startUpload(ref.sha256)?.also { dao.setUploadId(ref.sha256, it) } ?: return
        }
        val done = received.orEmpty()

        val parts = ((ref.size + partBytes - 1) / partBytes).toInt().coerceAtLeast(1)
        for (n in 1..parts) {
            if (n in done) continue
            val offset = (n - 1) * partBytes
            client.putPart(ref.sha256, id, n, file, offset, minOf(partBytes, ref.size - offset))
        }
        try {
            client.completeUpload(ref.sha256, id)
        } finally {
            // Completed, or refused: either way the upload is over on the server.
            dao.setUploadId(ref.sha256, null)
        }
    }

    companion object {
        /** About 8 MB, under the reverse proxy's body limit (#67). */
        const val PART_BYTES: Long = 8L * 1024 * 1024

        /** [SyncException.code] for a file that changed under the upload. */
        const val CHANGED: Int = -1
    }
}
