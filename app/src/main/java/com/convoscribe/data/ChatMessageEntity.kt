package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A message in the LLM conversation attached to a session.
 * [MessageKind.SUMMARY] holds the rolling session summary; there is
 * typically one row per session for that kind. [MessageKind.QA] holds
 * a user question paired with an assistant answer — two rows per
 * round, distinguished by [role].
 *
 * `createdAt` is the wall-clock time the row was produced; the
 * primary sort key for `observe(...)` is `createdAt ASC, id ASC`
 * so the messages replay in order even when several land in the same
 * millisecond.
 */
@Entity(
    tableName = "chat_messages",
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
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val kind: MessageKind,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    /**
     * Name of the template a SUMMARY row was written with (issue #27),
     * as it was called at the time; `null` for other kinds and for
     * summaries from before templates.
     */
    val templateName: String? = null
)
