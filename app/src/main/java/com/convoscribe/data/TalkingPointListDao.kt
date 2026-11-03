package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [TalkingPointListEntity] rows (issue #102), by name. */
@Dao
interface TalkingPointListDao {
    @Query("SELECT * FROM talking_point_lists ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<TalkingPointListEntity>>

    @Query("SELECT * FROM talking_point_lists ORDER BY name COLLATE NOCASE ASC, id ASC")
    suspend fun getAll(): List<TalkingPointListEntity>

    @Query("SELECT * FROM talking_point_lists WHERE id = :id")
    suspend fun get(id: Long): TalkingPointListEntity?

    @Insert
    suspend fun insert(entity: TalkingPointListEntity): Long

    @Query("UPDATE talking_point_lists SET name = :name, points = :points, updatedAt = :updatedAt WHERE id = :id")
    suspend fun update(id: Long, name: String, points: String, updatedAt: Long)

    @Query("DELETE FROM talking_point_lists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM talking_point_lists WHERE syncId = :syncId")
    suspend fun getBySyncId(syncId: String): TalkingPointListEntity?

    /** Give the item [from] the sync id [to]. */
    @Query("UPDATE talking_point_lists SET syncId = :to WHERE syncId = :from")
    suspend fun rekey(from: String, to: String)
}
