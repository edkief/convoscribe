package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [EnrolledSpeakerEntity] rows. The list is
 * expected to stay small (a handful of household voices), so the
 * whole table is loaded into memory when [getAll] runs.
 *
 * Since schema v3 the `name` index is not unique: a person may
 * have several rows, one per voice sample. Callers decide how many
 * to keep (see [EnrolledSpeakerRepository.save]).
 */
@Dao
interface EnrolledSpeakerDao {

    /**
     * Every enrolled speaker, alphabetical by name so the UI
     * list is stable.
     */
    @Query("SELECT * FROM enrolled_speakers ORDER BY name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<EnrolledSpeakerEntity>>

    /**
     * Snapshot read used by the diarization pipeline: it needs
     * the embedding vectors up-front, not a flow.
     */
    @Query("SELECT * FROM enrolled_speakers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<EnrolledSpeakerEntity>

    /** Insert one voice sample. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: EnrolledSpeakerEntity): Long

    /**
     * Keep only the [keep] newest voice samples stored under [name]. Ties
     * break on `syncId`, not the local id, so every device keeps the same
     * samples (issue #70).
     */
    @Query(
        "DELETE FROM enrolled_speakers WHERE name = :name AND id NOT IN " +
            "(SELECT id FROM enrolled_speakers WHERE name = :name ORDER BY createdAt DESC, syncId DESC LIMIT :keep)"
    )
    suspend fun pruneName(name: String, keep: Int)

    /** Remove every voice sample stored under [name]. */
    @Query("DELETE FROM enrolled_speakers WHERE name = :name")
    suspend fun deleteByName(name: String)

    @Query("DELETE FROM enrolled_speakers WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM enrolled_speakers WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): EnrolledSpeakerEntity?
}
