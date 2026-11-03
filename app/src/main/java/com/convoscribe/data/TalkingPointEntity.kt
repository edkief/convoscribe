package com.convoscribe.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A talking point of one conversation (issue #102): [text] the user
 * meant to raise, at [position] in the session's list.
 *
 * [covered] is whether it was raised, and [coveredAtMs] when, on the
 * recording's clock, if known. [byUser] says the user ticked or
 * unticked it by hand, so automatic checking (#103) leaves it alone.
 * [pointId] names the point on every device, so edits on two devices
 * merge point by point. The points go with their session
 * (`onDelete = CASCADE`).
 */
@Entity(
    tableName = "talking_points",
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
data class TalkingPointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val pointId: String = UUID.randomUUID().toString(),
    val text: String,
    val position: Int,
    val covered: Boolean = false,
    val coveredAtMs: Long? = null,
    val byUser: Boolean = false,
    val createdAt: Long
)
