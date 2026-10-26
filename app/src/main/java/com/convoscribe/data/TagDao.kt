package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Room access for [TagEntity] and [SessionTagEntity] rows (issue #32). */
@Dao
interface TagDao {
    /** Every tag, alphabetically. */
    @Query("SELECT * FROM tags ORDER BY nameKey ASC")
    fun observeAll(): Flow<List<TagEntity>>

    @Query("SELECT * FROM tags ORDER BY nameKey ASC")
    suspend fun getAll(): List<TagEntity>

    @Query(
        "SELECT t.id AS id, t.name AS name, COUNT(st.sessionId) AS sessionCount FROM tags t " +
            "LEFT JOIN session_tags st ON st.tagId = t.id GROUP BY t.id ORDER BY t.nameKey ASC"
    )
    fun observeUsage(): Flow<List<TagUsage>>

    @Query("SELECT * FROM tags WHERE nameKey = :key")
    suspend fun findByKey(key: String): TagEntity?

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun get(id: Long): TagEntity?

    @Insert
    suspend fun insert(entity: TagEntity): Long

    @Query("UPDATE tags SET name = :name, nameKey = :key WHERE id = :id")
    suspend fun rename(id: Long, name: String, key: String)

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    /** Every session–tag link with the tag's name, tags alphabetically. */
    @Query(
        "SELECT st.sessionId AS sessionId, t.id AS tagId, t.name AS name FROM session_tags st " +
            "JOIN tags t ON t.id = st.tagId ORDER BY t.nameKey ASC"
    )
    fun observeSessionTags(): Flow<List<SessionTagName>>

    @Query(
        "SELECT t.* FROM tags t JOIN session_tags st ON st.tagId = t.id " +
            "WHERE st.sessionId = :sessionId ORDER BY t.nameKey ASC"
    )
    fun observeForSession(sessionId: Long): Flow<List<TagEntity>>

    @Query("SELECT tagId FROM session_tags WHERE sessionId = :sessionId")
    suspend fun tagIdsFor(sessionId: Long): List<Long>

    @Query("SELECT sessionId FROM session_tags WHERE tagId = :tagId")
    suspend fun sessionIdsFor(tagId: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun link(links: List<SessionTagEntity>)

    @Query("DELETE FROM session_tags WHERE sessionId = :sessionId")
    suspend fun unlinkAll(sessionId: Long)
}

/**
 * Ids of the tags named [names] (cleaned with [TagNames.clean]),
 * creating the ones that do not exist yet. Matching ignores case, so
 * an existing tag keeps its spelling. Call inside a transaction.
 */
internal suspend fun TagDao.ensure(names: List<String>, now: Long): List<Long> = TagNames.clean(names).map { name ->
    val key = TagNames.key(name)
    findByKey(key)?.id ?: insert(TagEntity(name = name, nameKey = key, createdAt = now))
}
