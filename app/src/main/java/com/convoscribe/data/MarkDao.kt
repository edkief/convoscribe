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

    /** Replace the note; the full note written from the old one goes with it (issue #48). */
    @Query(
        "UPDATE marks SET note = :note, expandedNote = NULL, expandedAt = NULL, expandedStale = 0 WHERE id = :id"
    )
    suspend fun setNote(id: Long, note: String?)

    @Query("UPDATE marks SET expandedNote = :text, expandedAt = :at, expandedStale = 0 WHERE id = :id")
    suspend fun setExpanded(id: Long, text: String, at: Long)

    /** Flag every written full note of [sessionId] as outdated (issue #48). */
    @Query("UPDATE marks SET expandedStale = 1 WHERE sessionId = :sessionId AND expandedNote IS NOT NULL")
    suspend fun markExpansionsStale(sessionId: Long)

    @Query("DELETE FROM marks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM marks WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
