package com.convoscribe.data

import androidx.room.Entity

/** Kinds of synced document (issue #67); #70 adds known voices, presets and templates. */
object SyncKinds {
    const val SESSION: String = "session"
}

/**
 * What the sync server last accepted for one document (issue #68):
 * revision [baseRevision], whose manifest hashed to [baseHash]. The
 * document needs pushing while its manifest hashes to anything else.
 *
 * [pendingHash] is the changed hash sync first saw, at [pendingSince];
 * it stands in for when the document changed until every write path
 * stamps `updatedAt` (#69). [lastError] is the last push's failure,
 * and [conflict] is set when the server holds a newer revision from
 * another device, which waits for two-way sync (#69).
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
    val conflict: Boolean = false
)

/**
 * A document deleted here after the server had it (issue #68), at
 * [baseRevision]; sync deletes it on the server, then drops this.
 */
@Entity(tableName = "sync_tombstones", primaryKeys = ["kind", "syncId"])
data class SyncTombstoneEntity(
    val kind: String,
    val syncId: String,
    val baseRevision: Long,
    val deletedAt: Long
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
