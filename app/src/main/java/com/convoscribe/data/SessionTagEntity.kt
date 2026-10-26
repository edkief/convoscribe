package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Links a session to a tag (issue #32). Both foreign keys cascade, so
 * deleting a session or a tag removes its links.
 */
@Entity(
    tableName = "session_tags",
    primaryKeys = ["sessionId", "tagId"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = TagEntity::class,
            parentColumns = ["id"],
            childColumns = ["tagId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("tagId")]
)
data class SessionTagEntity(
    val sessionId: Long,
    val tagId: Long
)
