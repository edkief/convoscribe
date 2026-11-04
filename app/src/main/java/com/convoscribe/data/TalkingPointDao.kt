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

    /** Each session's number of points and how many are covered, for Home's cards (issue #108). */
    @Query(
        "SELECT sessionId, COUNT(*) AS total, SUM(CASE WHEN covered THEN 1 ELSE 0 END) AS covered " +
            "FROM talking_points GROUP BY sessionId"
    )
    fun observeCounts(): Flow<List<TalkingPointCount>>

    /**
     * Points still not covered in sessions recorded since [sinceMs],
     * newest session first and in each session's order, at most [limit]
     * (issue #108, *Not covered last time*).
     */
    @Query(
        "SELECT talking_points.id AS pointId, talking_points.sessionId AS sessionId, talking_points.text AS text, " +
            "sessions.title AS sessionTitle, sessions.createdAt AS sessionCreatedAt " +
            "FROM talking_points JOIN sessions ON sessions.id = talking_points.sessionId " +
            "WHERE talking_points.covered = 0 AND sessions.createdAt >= :sinceMs " +
            "ORDER BY sessions.createdAt DESC, talking_points.position ASC, talking_points.id ASC LIMIT :limit"
    )
    fun observeRecentUncovered(sinceMs: Long, limit: Int): Flow<List<UncoveredPoint>>

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

/** A session's [total] talking points and how many are [covered] (issue #108). */
data class TalkingPointCount(val sessionId: Long, val total: Int, val covered: Int)

/** A point not covered yet, with the session it belongs to (issue #108). */
data class UncoveredPoint(
    val pointId: Long,
    val sessionId: Long,
    val text: String,
    val sessionTitle: String,
    val sessionCreatedAt: Long
)
