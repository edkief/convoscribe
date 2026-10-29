package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room access for [ChatMessageEntity] rows. Each "ask the LLM" round
 * inserts two rows (USER + ASSISTANT) and a session summary is a
 * single ASSISTANT row with `kind = SUMMARY`.
 */
@Dao
interface ChatMessageDao {
    @Insert
    suspend fun insert(entity: ChatMessageEntity): Long

    /** Every message of a session, both kinds, in the order they were written (issue #25). */
    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<ChatMessageEntity>

    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    /**
     * Chat messages for a session, in playback order. The primary
     * sort is [createdAt]; ties are broken by the auto-generated
     * [id] so messages written in the same millisecond still replay
     * in the order they were inserted.
     */
    @Query(
        "SELECT * FROM chat_messages WHERE sessionId = :sessionId AND kind = :kind " +
            "ORDER BY createdAt ASC, id ASC"
    )
    fun observe(sessionId: Long, kind: MessageKind): Flow<List<ChatMessageEntity>>

    /**
     * Remove every chat message of [kind] for a session. Used by
     * "Re-summarise" to drop the previous summary before inserting
     * a new one.
     */
    @Query("DELETE FROM chat_messages WHERE sessionId = :sessionId AND kind = :kind")
    suspend fun deleteKind(sessionId: Long, kind: MessageKind)
}
