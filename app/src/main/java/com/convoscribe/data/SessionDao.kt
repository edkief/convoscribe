package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [SessionEntity] rows. The reactive methods return
 * Flows so the UI re-renders the session list as the recorder creates
 * new sessions or diarization finishes. The other methods are
 * `suspend` so they can only be called from a coroutine.
 */
@Dao
interface SessionDao {
    @Insert
    suspend fun insert(entity: SessionEntity): Long

    @Update
    suspend fun update(entity: SessionEntity)

    /** All sessions, newest first. The home screen watches this. */
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    /** A single session, or `null` if the id does not exist. */
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionEntity?>

    /** Synchronous getter; used by the recorder when it needs the row. */
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionEntity?

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * Sessions in a given status. Used by the diarization retry
     * loop to find sessions that ended in [SessionStatus.DIARIZATION_FAILED].
     */
    @Query("SELECT * FROM sessions WHERE status = :status")
    suspend fun findByStatus(status: SessionStatus): List<SessionEntity>

    @Query("UPDATE sessions SET status = :status WHERE id = :id")
    suspend fun setStatus(id: Long, status: SessionStatus)

    /**
     * Stamp the final duration on a session. Used by the
     * crash-recovery loop (TASK-19) to back-fill the duration of a
     * WAV that was left half-closed when the process was killed;
     * the recorder itself sets the duration together with the
     * status in [SessionRepository.finishRecording].
     */
    @Query("UPDATE sessions SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)

    @Query("UPDATE sessions SET numSpeakers = :numSpeakers WHERE id = :id")
    suspend fun setNumSpeakers(id: Long, numSpeakers: Int?)

    /** Expected participants, already encoded by [ParticipantsCodec]. */
    @Query("UPDATE sessions SET participants = :participants WHERE id = :id")
    suspend fun setParticipants(id: Long, participants: String?)

    @Query("UPDATE sessions SET asrEngine = :engine, asrModel = :model WHERE id = :id")
    suspend fun setEngine(id: Long, engine: String?, model: String?)

    @Query("UPDATE sessions SET language = :language WHERE id = :id")
    suspend fun setLanguage(id: Long, language: String)

    @Query("DELETE FROM segments WHERE sessionId = :id")
    suspend fun deleteSegments(id: Long)

    /**
     * Update the user-visible [title] of a session. Called by the
     * session-detail screen's "Rename session" menu; the
     * repository enforces the 100-character cap so the DAO stays
     * free of business rules.
     */
    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)
}
