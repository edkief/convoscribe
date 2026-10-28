package com.convoscribe.data

import androidx.room.Dao
import androidx.room.Query

/**
 * Read-only queries behind cross-session search (issue #24).
 * Transcript lines are searched through the [SegmentFtsEntity] index;
 * titles, speaker names and summaries are a handful of rows per
 * session, so [SessionSearch] loads them with [sessions], [speakers]
 * and [summaries] and matches them in Kotlin with the same folding.
 */
@Dao
interface SearchDao {
    /**
     * Transcript lines matching the FTS4 [match] expression (see
     * [SearchText.ftsQuery]), newest session first and in reading
     * order within a session. [language] `null` means any; only
     * sessions created at or after [since] are considered. The
     * snippet marks each matched word with [open] / [close].
     */
    @Query(
        "SELECT s.sessionId AS sessionId, s.id AS segmentId, s.startMs AS startMs, " +
            "s.speakerIndex AS speakerIndex, " +
            "snippet(segments_fts, :open, :close, '…', -1, 24) AS snippet " +
            "FROM segments_fts " +
            "JOIN segments s ON s.id = segments_fts.rowid " +
            "JOIN sessions ss ON ss.id = s.sessionId " +
            "WHERE segments_fts MATCH :match " +
            "AND (:language IS NULL OR ss.language = :language) " +
            "AND ss.createdAt >= :since " +
            "ORDER BY ss.createdAt DESC, ss.id DESC, s.startMs ASC " +
            "LIMIT :limit"
    )
    suspend fun findSegments(
        match: String,
        language: String?,
        since: Long,
        open: String,
        close: String,
        limit: Int
    ): List<SegmentHit>

    /**
     * Sessions with transcript lines matching [match] (issue #49),
     * the most matching lines first, then newest first; at most
     * [limit]. [language] and [since] narrow them as in [findSegments].
     */
    @Query(
        "SELECT s.sessionId AS sessionId, COUNT(*) AS hits " +
            "FROM segments_fts " +
            "JOIN segments s ON s.id = segments_fts.rowid " +
            "JOIN sessions ss ON ss.id = s.sessionId " +
            "WHERE segments_fts MATCH :match " +
            "AND (:language IS NULL OR ss.language = :language) " +
            "AND ss.createdAt >= :since " +
            "GROUP BY s.sessionId " +
            "ORDER BY hits DESC, ss.createdAt DESC, ss.id DESC " +
            "LIMIT :limit"
    )
    suspend fun matchingSessions(match: String, language: String?, since: Long, limit: Int): List<SessionHits>

    /** Every session, newest first. */
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC, id DESC")
    suspend fun sessions(): List<SessionEntity>

    /** Every speaker of every session. */
    @Query("SELECT * FROM speakers ORDER BY sessionId, speakerIndex")
    suspend fun speakers(): List<SpeakerEntity>

    /** Saved summaries, oldest first, so the last one per session wins. */
    @Query(
        "SELECT * FROM chat_messages WHERE kind = 'SUMMARY' AND role = 'ASSISTANT' " +
            "ORDER BY createdAt ASC, id ASC"
    )
    suspend fun summaries(): List<ChatMessageEntity>

    /** Every session–tag link (issue #32). */
    @Query("SELECT * FROM session_tags")
    suspend fun sessionTags(): List<SessionTagEntity>

    /** Tags on at least one session, alphabetically. */
    @Query("SELECT * FROM tags WHERE id IN (SELECT tagId FROM session_tags) ORDER BY nameKey ASC")
    suspend fun usedTags(): List<TagEntity>
}

/** Row of [SearchDao.findSegments]. */
data class SegmentHit(
    val sessionId: Long,
    val segmentId: Long,
    val startMs: Long,
    val speakerIndex: Int?,
    val snippet: String
)

/** Row of [SearchDao.matchingSessions]: how many lines of a session matched. */
data class SessionHits(val sessionId: Long, val hits: Int)
