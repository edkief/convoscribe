package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [ActionItemEntity] rows (issue #31), in the order the summary listed them. */
@Dao
interface ActionItemDao {
    /** The items the user sees: every one but the deleted. */
    @Query("SELECT * FROM action_items WHERE sessionId = :sessionId AND deleted = 0 ORDER BY position ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<ActionItemEntity>>

    /** Every item, deleted ones included, as sync and regenerating need them. */
    @Query("SELECT * FROM action_items WHERE sessionId = :sessionId ORDER BY position ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<ActionItemEntity>

    /** Items neither done nor deleted across sessions, newest session first. */
    @Query(
        "SELECT action_items.*, sessions.title AS sessionTitle, sessions.createdAt AS sessionCreatedAt " +
            "FROM action_items JOIN sessions ON sessions.id = action_items.sessionId " +
            "WHERE action_items.done = 0 AND action_items.deleted = 0 " +
            "ORDER BY sessions.createdAt DESC, action_items.position ASC, action_items.id ASC"
    )
    fun observeOpen(): Flow<List<OpenActionItem>>

    @Insert
    suspend fun insertAll(items: List<ActionItemEntity>)

    @Query("DELETE FROM action_items WHERE id IN (:ids)")
    suspend fun deleteIds(ids: List<Long>)

    @Query("UPDATE action_items SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("UPDATE action_items SET text = :text, owner = :owner, dueDate = :dueDate, edited = 1 WHERE id = :id")
    suspend fun edit(id: Long, text: String, owner: String?, dueDate: String?)

    @Query("UPDATE action_items SET deleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Long)

    @Query("DELETE FROM action_items WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)
}
