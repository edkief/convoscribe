package com.convoscribe.data

import kotlinx.coroutines.flow.Flow

/**
 * A session's talking points as the Record screen uses them (issue
 * #102); [SessionRepository] in production.
 */
interface SessionTalkingPoints {
    fun observeTalkingPoints(sessionId: Long): Flow<List<TalkingPointEntity>>

    /** Tick or untick by hand; [atMs] is when it was covered on the recording's clock. */
    suspend fun setTalkingPointCovered(pointId: Long, covered: Boolean, atMs: Long? = null)

    /** Add a point at the end; `null` for a blank [text]. */
    suspend fun addTalkingPoint(sessionId: Long, text: String): Long?
}
