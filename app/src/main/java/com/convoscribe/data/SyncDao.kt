package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Sync bookkeeping (issue #68): [SyncStateEntity], tombstones and file hashes. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE kind = :kind AND syncId = :syncId")
    suspend fun state(kind: String, syncId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE kind = :kind")
    suspend fun states(kind: String): List<SyncStateEntity>

    @Upsert
    suspend fun upsertState(state: SyncStateEntity)

    /** Documents the server has a newer revision of (#69). */
    @Query("SELECT * FROM sync_state WHERE kind = :kind AND remoteRevision > baseRevision AND excluded = 0")
    suspend fun behind(kind: String): List<SyncStateEntity>

    /** Documents with a recording still to download (#69). */
    @Query("SELECT * FROM sync_state WHERE kind = :kind AND (audioSha IS NOT NULL OR cleanSha IS NOT NULL)")
    suspend fun pendingAudio(kind: String): List<SyncStateEntity>

    /** The sync ids of sessions whose recording is still downloading (#69). */
    @Query("SELECT syncId FROM sync_state WHERE kind = 'session' AND audioSha IS NOT NULL AND excluded = 0")
    fun observeAudioPending(): Flow<List<String>>

    /** The sync ids of sessions the server has, so they can be removed from this device only (#69). */
    @Query("SELECT syncId FROM sync_state WHERE kind = 'session' AND baseRevision > 0 AND excluded = 0")
    fun observeSynced(): Flow<List<String>>

    /** Sessions removed from this device but kept on the server, newest first (#69). */
    @Query("SELECT * FROM sync_state WHERE kind = 'session' AND excluded = 1 ORDER BY createdAt DESC")
    fun observeExcluded(): Flow<List<SyncStateEntity>>

    @Query("DELETE FROM sync_state WHERE kind = :kind AND syncId = :syncId")
    suspend fun deleteState(kind: String, syncId: String)

    @Upsert
    suspend fun upsertTombstone(tombstone: SyncTombstoneEntity)

    @Query("SELECT * FROM sync_tombstones ORDER BY deletedAt")
    suspend fun tombstones(): List<SyncTombstoneEntity>

    @Query("DELETE FROM sync_tombstones WHERE kind = :kind AND syncId = :syncId")
    suspend fun deleteTombstone(kind: String, syncId: String)

    @Query("SELECT * FROM file_hashes WHERE path = :path")
    suspend fun fileHash(path: String): FileHashEntity?

    @Upsert
    suspend fun upsertFileHash(hash: FileHashEntity)

    @Query("UPDATE file_hashes SET uploadId = :uploadId WHERE sha256 = :sha256")
    suspend fun setUploadId(sha256: String, uploadId: String?)

    @Query("SELECT uploadId FROM file_hashes WHERE sha256 = :sha256 AND uploadId IS NOT NULL LIMIT 1")
    suspend fun uploadId(sha256: String): String?

    @Query("DELETE FROM file_hashes WHERE path = :path")
    suspend fun deleteFileHash(path: String)
}
