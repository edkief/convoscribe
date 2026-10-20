package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [MarkEntity] rows, in recorded-time order. */
@Dao
interface MarkDao {
    @Query("SELECT * FROM marks WHERE sessionId = :sessionId ORDER BY atMs ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<MarkEntity>>

    @Query("SELECT * FROM marks WHERE sessionId = :sessionId ORDER BY atMs ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<MarkEntity>

    @Query("SELECT COUNT(*) FROM marks WHERE sessionId = :sessionId")
    suspend fun count(sessionId: Long): Int

    @Insert
    suspend fun insert(entity: MarkEntity): Long

    @Query("UPDATE marks SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("DELETE FROM marks WHERE id = :id")
    suspend fun delete(id: Long)
}
