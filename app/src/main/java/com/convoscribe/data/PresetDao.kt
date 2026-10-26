package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** Room access for [PresetEntity] rows, alphabetically. */
@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name COLLATE NOCASE ASC, id ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE id = :id")
    suspend fun get(id: Long): PresetEntity?

    @Insert
    suspend fun insert(entity: PresetEntity): Long

    @Update
    suspend fun update(entity: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: Long)
}
