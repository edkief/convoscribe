package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [SpeakerEntity] rows. The list is small (one row
 * per detected speaker, usually 1..5), so [upsert] replaces the whole
 * set in one call when diarization finishes.
 */
@Dao
interface SpeakerDao {
    /**
     * Insert or replace every row in [list]. Used by the repository
     * to apply a fresh diarization result.
     */
    @Upsert
    suspend fun upsert(list: List<SpeakerEntity>)

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId ORDER BY speakerIndex ASC")
    fun observeForSession(sessionId: Long): Flow<List<SpeakerEntity>>

    @Query("SELECT * FROM speakers WHERE sessionId = :sessionId ORDER BY speakerIndex ASC")
    suspend fun getForSession(sessionId: Long): List<SpeakerEntity>

    /** How many speakers each diarized session has; sessions without speakers are absent. */
    @Query("SELECT sessionId, COUNT(*) AS count FROM speakers GROUP BY sessionId")
    fun observeCounts(): Flow<List<SpeakerCount>>

    @Query("DELETE FROM speakers WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    /**
     * Update the user-visible [label] for one speaker within a
     * session. The repository guarantees the row exists (it is
     * created by diarization) before the user can rename it.
     */
    @Query(
        "UPDATE speakers SET label = :label WHERE sessionId = :sessionId AND speakerIndex = :speakerIndex"
    )
    suspend fun rename(sessionId: Long, speakerIndex: Int, label: String)
}

/** Row of [SpeakerDao.observeCounts]. */
data class SpeakerCount(val sessionId: Long, val count: Int)
