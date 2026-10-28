package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for the *Ask across sessions* history (issue #49). */
@Dao
interface GlobalChatMessageDao {
    @Insert
    suspend fun insert(entity: GlobalChatMessageEntity): Long

    /** The whole history, oldest first; ties replay in insertion order. */
    @Query("SELECT * FROM global_chat_messages ORDER BY createdAt ASC, id ASC")
    fun observe(): Flow<List<GlobalChatMessageEntity>>

    @Query("DELETE FROM global_chat_messages")
    suspend fun deleteAll()
}
