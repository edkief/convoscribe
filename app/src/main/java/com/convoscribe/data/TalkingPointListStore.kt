package com.convoscribe.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** A talking-point list as the app sees it (issue #102). */
data class TalkingPointList(val id: Long, val name: String, val points: List<String>)

/**
 * The user's talking-point lists (issue #102). Names and points are
 * cleaned with [TalkingPoints.clean] and [TalkingPoints.cleanAll].
 */
interface TalkingPointListStore {
    /** Every list, by name. */
    fun observeAll(): Flow<List<TalkingPointList>>

    /** Add a list; returns its id. */
    suspend fun add(name: String, points: List<String>): Long

    /** Change a list's name and points. */
    suspend fun update(id: Long, name: String, points: List<String>)

    suspend fun delete(id: Long)
}

/** Room-backed [TalkingPointListStore]. */
class TalkingPointListRepository(
    private val dao: TalkingPointListDao,
    private val clock: () -> Long = System::currentTimeMillis
) : TalkingPointListStore {

    override fun observeAll(): Flow<List<TalkingPointList>> = dao.observeAll().map { rows -> rows.map { it.toList() } }

    override suspend fun add(name: String, points: List<String>): Long = dao.insert(
        TalkingPointListEntity(
            name = TalkingPoints.clean(name),
            points = TalkingPoints.join(points),
            createdAt = clock()
        )
    )

    override suspend fun update(id: Long, name: String, points: List<String>) =
        dao.update(id, TalkingPoints.clean(name), TalkingPoints.join(points), clock())

    override suspend fun delete(id: Long) = dao.delete(id)

    private fun TalkingPointListEntity.toList() = TalkingPointList(id, name, TalkingPoints.split(points))
}

/** How talking points are written down (issue #102). */
object TalkingPoints {
    /** Longest point kept, in characters. */
    const val MAX_LEN: Int = 300

    /** [text] on one line: whitespace collapsed, trimmed and capped at [MAX_LEN]. */
    fun clean(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(MAX_LEN).trim()

    /** [texts] cleaned, without blanks. */
    fun cleanAll(texts: List<String>): List<String> = texts.map(::clean).filter { it.isNotEmpty() }

    /** [texts] cleaned and one per line, as a list stores them. */
    fun join(texts: List<String>): String = cleanAll(texts).joinToString("\n")

    /** A list's stored points, in order. */
    fun split(stored: String): List<String> = cleanAll(stored.split('\n'))

    /** [extra] appended to [current], leaving out points [current] already has (ignoring case and accents). */
    fun append(current: List<String>, extra: List<String>): List<String> {
        val seen = current.map(::key).toMutableSet()
        val out = current.toMutableList()
        for (point in cleanAll(extra)) if (seen.add(key(point))) out += point
        return out
    }

    private fun key(text: String): String = SearchText.fold(clean(text))
}
