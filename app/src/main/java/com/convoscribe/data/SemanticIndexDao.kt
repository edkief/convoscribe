package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Room access for the semantic search index (issue #61). */
@Dao
interface SemanticIndexDao {

    /** Every passage vector made by [modelId]. */
    @Query("SELECT * FROM passage_vectors WHERE modelId = :modelId")
    suspend fun vectors(modelId: String): List<PassageVectorEntity>

    /** How many passage vectors [modelId] made; changes whenever a session is indexed or deleted. */
    @Query("SELECT COUNT(*) FROM passage_vectors WHERE modelId = :modelId")
    suspend fun vectorCount(modelId: String): Int

    /** Every session's index state. */
    @Query("SELECT * FROM session_index_state")
    suspend fun states(): List<SessionIndexStateEntity>

    @Insert
    suspend fun insertVectors(vectors: List<PassageVectorEntity>)

    @Upsert
    suspend fun upsertState(state: SessionIndexStateEntity)

    @Query("DELETE FROM passage_vectors WHERE sessionId = :sessionId")
    suspend fun deleteVectors(sessionId: Long)

    /** Replace [sessionId]'s passages with [vectors], made from [state]. */
    @Transaction
    suspend fun replace(sessionId: Long, vectors: List<PassageVectorEntity>, state: SessionIndexStateEntity) {
        deleteVectors(sessionId)
        insertVectors(vectors)
        upsertState(state)
    }

    /** Forget the whole index, as when the model is removed. */
    @Transaction
    suspend fun clear() {
        clearVectors()
        clearStates()
    }

    @Query("DELETE FROM passage_vectors")
    suspend fun clearVectors()

    @Query("DELETE FROM session_index_state")
    suspend fun clearStates()
}
