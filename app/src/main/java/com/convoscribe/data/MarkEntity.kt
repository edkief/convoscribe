package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A moment the user marked while recording (issue #30). [atMs] is a
 * position in the recorded audio, the same clock as the segments'
 * times, so it maps straight onto playback and the transcript. [note]
 * is an optional short note, `null` when there is none.
 *
 * Marks do not belong to a segment: re-transcribing, diarizing or
 * editing the transcript leaves them where they are. Like the
 * segments they go with their session (`onDelete = CASCADE`).
 */
@Entity(
    tableName = "marks",
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
data class MarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val atMs: Long,
    val note: String? = null,
    val createdAt: Long
)
