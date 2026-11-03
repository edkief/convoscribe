package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [TalkingPointEntity] rows (issue #102), in their order. */
@Dao
interface TalkingPointDao {
    @Query("SELECT * FROM talking_points WHERE sessionId = :sessionId ORDER BY position ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<TalkingPointEntity>>

    @Query("SELECT * FROM talking_points WHERE sessionId = :sessionId ORDER BY position ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<TalkingPointEntity>

    @Query("SELECT * FROM talking_points WHERE id = :id")
    suspend fun get(id: Long): TalkingPointEntity?

    @Insert
    suspend fun insertAll(points: List<TalkingPointEntity>)

    @Query("UPDATE talking_points SET covered = :covered, coveredAtMs = :atMs, byUser = :byUser WHERE id = :id")
    suspend fun setCovered(id: Long, covered: Boolean, atMs: Long?, byUser: Boolean)

    @Query("UPDATE talking_points SET text = :text WHERE id = :id")
    suspend fun setText(id: Long, text: String)

    @Query("UPDATE talking_points SET position = :position WHERE id = :id")
    suspend fun setPosition(id: Long, position: Int)

    @Query("DELETE FROM talking_points WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM talking_points WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
