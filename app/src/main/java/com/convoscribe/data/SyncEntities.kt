package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity

/** Kinds of synced document (issue #67). */
object SyncKinds {
    const val SESSION: String = "session"

    /** One known-voice sample (#70). */
    const val VOICE: String = "voice"
    const val PRESET: String = "preset"

    /** A summary template the user wrote. */
    const val TEMPLATE: String = "template"

    /** A session's semantic vectors, under the session's sync id. */
    const val VECTORS: String = "vectors"
}

/**
 * What the sync server last accepted for one document (issue #68):
 * revision [baseRevision], whose manifest hashed to [baseHash]. The
 * document needs pushing while its manifest hashes to anything else.
 *
 * [pendingHash] is the changed hash sync first saw, at [pendingSince],
 * for a session changed before `updatedAt` was stamped (#69).
 * [lastError] is the last push's failure.
 *
 * Two-way sync (issue #69): [remoteRevision] is the newest revision
 * the change feed has shown ([remoteDeleted] when it is a delete), so
 * the document is behind while it exceeds [baseRevision]. [conflict]
 * is set when the server's version won the conflict rule against a
 * push from here, and replaces the local one at the next download.
 * [excluded] is *Remove from this device*: the session stays on the
 * server, is not downloaded again until asked, and the home list shows
 * it from [title], [createdAt] and [durationMs]. [audioSha] and
 * [cleanSha] (with their sizes) are recordings of a downloaded session
 * still to fetch.
 *
 * [contentKey] is, for a [SyncKinds.VECTORS] document, the text hash
 * of the server's copy (issue #70), so a device leaves alone vectors
 * another model made from the same text.
 *
 * Keyed by sync id, not the local row, so it outlives a delete long
 * enough to leave a [SyncTombstoneEntity].
 */
@Entity(tableName = "sync_state", primaryKeys = ["kind", "syncId"])
data class SyncStateEntity(
    val kind: String,
    val syncId: String,
    val baseRevision: Long = 0,
    val baseHash: String? = null,
    val pendingHash: String? = null,
    val pendingSince: Long? = null,
    val lastError: String? = null,
    val conflict: Boolean = false,
    @ColumnInfo(defaultValue = "0") val remoteRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val remoteDeleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val excluded: Boolean = false,
    val title: String? = null,
    val createdAt: Long? = null,
    val durationMs: Long? = null,
    val audioSha: String? = null,
    val audioSize: Long? = null,
    val cleanSha: String? = null,
    val cleanSize: Long? = null,
    val contentKey: String? = null
) {
    /** The server has a revision this device has not applied. */
    val behind: Boolean get() = remoteRevision > baseRevision

    /** Recordings still to download, as references. */
    val pendingAudio: SessionManifest.FileRef?
        get() = audioSha?.let { SessionManifest.FileRef(it, audioSize ?: 0) }
    val pendingClean: SessionManifest.FileRef?
        get() = cleanSha?.let { SessionManifest.FileRef(it, cleanSize ?: 0) }
}

/**
 * A document deleted here after the server had it (issue #68), at
 * [baseRevision]; sync deletes it on the server, then drops this.
 * [durationMs] is how long the deleted recording was, which the
 * conflict rule weighs against a version this device never saw (#69);
 * `null` for a delete made before #69.
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "syncId"])
data class SyncTombstoneEntity(
    val kind: String,
    val syncId: String,
    val baseRevision: Long,
    val deletedAt: Long,
    val durationMs: Long? = null
)

/**
 * The SHA-256 of a recording as of its [size] and [lastModified]
 * (issue #68), so sync hashes a file once rather than on every run.
 * [uploadId] is the server's upload of it in progress, if any, so an
 * interrupted upload resumes.
 */
@Entity(tableName = "file_hashes", primaryKeys = ["path"])
data class FileHashEntity(
    val path: String,
    val size: Long,
    val lastModified: Long,
    val sha256: String,
    val uploadId: String? = null
)
