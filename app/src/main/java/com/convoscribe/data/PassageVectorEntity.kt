package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * The vector of one passage of a session, for semantic search (issue
 * #61). A passage is a run of transcript lines, [firstLine] to
 * [lastLine] (indices into the session's lines as
 * [TranscriptFormatter.lines] lists them), or the session's summary,
 * with both `-1`. [vector] is the float32 vector ([toBytes]) that the
 * model [modelId] gave. Deleting the session deletes its passages.
 */
@Entity(
    tableName = "passage_vectors",
    primaryKeys = ["sessionId", "passageIndex"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("modelId")]
)
data class PassageVectorEntity(
    val sessionId: Long,
    val passageIndex: Int,
    val firstLine: Int,
    val lastLine: Int,
    val modelId: String,
    val vector: ByteArray
) {
    // A ByteArray compares by identity; rows are equal by their key and content.
    override fun equals(other: Any?): Boolean = other is PassageVectorEntity &&
        sessionId == other.sessionId && passageIndex == other.passageIndex &&
        firstLine == other.firstLine && lastLine == other.lastLine &&
        modelId == other.modelId && vector.contentEquals(other.vector)

    override fun hashCode(): Int = (sessionId * 31 + passageIndex).hashCode()
}

/**
 * What a session's [PassageVectorEntity] rows were made from (issue
 * #61): the model [modelId] and [textHash], a hash of the passage
 * texts. When either differs from what the session would give now
 * (the transcript or summary changed, or another model is installed),
 * the session is indexed again.
 */
@Entity(
    tableName = "session_index_state",
    primaryKeys = ["sessionId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class SessionIndexStateEntity(
    val sessionId: Long,
    val modelId: String,
    val textHash: String
)
