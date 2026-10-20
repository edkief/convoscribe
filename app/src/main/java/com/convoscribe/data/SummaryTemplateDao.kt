package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [SummaryTemplateEntity] rows, in the order they were added. */
@Dao
interface SummaryTemplateDao {
    @Query("SELECT * FROM summary_templates ORDER BY createdAt ASC, id ASC")
    fun observeAll(): Flow<List<SummaryTemplateEntity>>

    @Query("SELECT * FROM summary_templates ORDER BY createdAt ASC, id ASC")
    suspend fun getAll(): List<SummaryTemplateEntity>

    @Insert
    suspend fun insert(entity: SummaryTemplateEntity): Long

    @Query("UPDATE summary_templates SET name = :name, body = :body WHERE id = :id")
    suspend fun update(id: Long, name: String, body: String)

    @Query("DELETE FROM summary_templates WHERE id = :id")
    suspend fun delete(id: Long)
}
