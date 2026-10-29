package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Sync bookkeeping (issue #68): [SyncStateEntity], tombstones and file hashes. */
@Dao
interface SyncDao {
    @Query("SELECT * FROM sync_state WHERE kind = :kind AND syncId = :syncId")
    suspend fun state(kind: String, syncId: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE kind = :kind")
    suspend fun states(kind: String): List<SyncStateEntity>

    @Upsert
    suspend fun upsertState(state: SyncStateEntity)

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
