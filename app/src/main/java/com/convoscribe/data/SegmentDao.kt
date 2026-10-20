package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [SegmentEntity] rows. The transcript UI watches
 * [observeForSession] so it can re-render line by line while ASR
 * streams in; [setSpeaker] is how the diarization result is written
 * back once the off-line pass finishes.
 */
@Dao
interface SegmentDao {
    @Insert
    suspend fun insert(entity: SegmentEntity): Long

    /**
     * Segments for a session, ordered by their start time so the
     * transcript reads top-to-bottom. The Flow re-emits whenever a
     * row in `segments` for this session is inserted, updated or
     * deleted.
     */
    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs ASC")
    fun observeForSession(sessionId: Long): Flow<List<SegmentEntity>>

    /**
     * Synchronous fetch, used by the LLM client when building a
     * transcript prompt (it does not need to observe).
     */
    @Query("SELECT * FROM segments WHERE sessionId = :sessionId ORDER BY startMs ASC")
    suspend fun getForSession(sessionId: Long): List<SegmentEntity>

    /**
     * Assign (or clear, when [speakerIndex] is `null`) the speaker
     * label on a single segment. Used by the diarization write-back
     * to set the index one segment at a time.
     */
    @Query("DELETE FROM segments WHERE id = :segmentId")
    suspend fun delete(segmentId: Long)

    @Query("UPDATE segments SET speakerIndex = :speakerIndex WHERE id = :segmentId")
    suspend fun setSpeaker(segmentId: Long, speakerIndex: Int?)

    @Query("SELECT * FROM segments WHERE id = :segmentId")
    suspend fun get(segmentId: Long): SegmentEntity?

    /**
     * Rewrite a line edited by hand (issue #26). A plain `UPDATE`, never
     * an insert-or-replace: the FTS index triggers only follow updates,
     * inserts and deletes.
     */
    @Update
    suspend fun update(entity: SegmentEntity)
}
