package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One transcript line: a stretch of speech between two ASR endpoints
 * (roughly one utterance) along with the speaker label assigned by
 * diarization. [speakerIndex] is `null` until diarization has run;
 * after that it is a 0-based index into the session's [SpeakerEntity]
 * rows (or `null` again if the segment was unlabelled).
 *
 * The `onDelete = CASCADE` foreign key means deleting a session
 * drops its segments in the same transaction, which is what the
 * repository relies on.
 */
@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class SegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val speakerIndex: Int? = null,
    /**
     * The line's tokens with absolute times, encoded by
     * [TokenTimingCodec]; `null` for lines recorded before schema v3
     * or by a backend without timings. Lets diarization split a line
     * at a speaker change.
     */
    val tokens: String? = null,
    /**
     * The user changed this line's text, or merged or split it
     * (issue #26). Diarization then never splits it, since a split
     * rebuilds the text from the tokens.
     */
    @ColumnInfo(defaultValue = "0")
    val textEdited: Boolean = false,
    /**
     * The user picked this line's speaker (issue #26); diarization
     * keeps that speaker instead of assigning one.
     */
    @ColumnInfo(defaultValue = "0")
    val speakerEdited: Boolean = false
)

/**
 * One part of a transcript line that diarization split at a speaker
 * change; it replaces the original row (see
 * [SessionRepository.applyDiarization]).
 */
data class SegmentPiece(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val tokens: List<com.convoscribe.asr.TimedToken>,
    val speakerIndex: Int?
)
