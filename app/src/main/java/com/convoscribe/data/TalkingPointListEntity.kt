package com.convoscribe.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A list of talking points the user keeps (issue #102): a [name] and
 * its [points], one per line in their order. A conversation that a
 * list is attached to gets its own copy of the points
 * ([TalkingPointEntity]), so ticking them never changes the list and
 * editing the list never changes past sessions.
 *
 * Like a summary template it syncs as a library item: [syncId] names it
 * on every device and [updatedAt] is when it last changed.
 */
@Entity(tableName = "talking_point_lists", indices = [Index(value = ["syncId"], unique = true)])
data class TalkingPointListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val points: String,
    val createdAt: Long,
    val syncId: String = UUID.randomUUID().toString(),
    val updatedAt: Long = createdAt
)
