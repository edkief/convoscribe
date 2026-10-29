package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Saved conversation options for a recurring meeting (issue #32), see
 * [Preset]. [titlePattern] may hold `{date}` and `{time}`;
 * [participants] is encoded by [ParticipantsCodec]; [tagIds] are tag
 * ids, one per line, and ids of deleted tags are skipped on read.
 *
 * [syncId] names the preset on every device (issue #70; a random one
 * on upgrade, [MIGRATION_18_19]) and [updatedAt] is when it last
 * changed, for sync's "most recent wins".
 */
@Entity(tableName = "presets", indices = [Index(value = ["syncId"], unique = true)])
data class PresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val titlePattern: String,
    val language: String,
    val numSpeakers: Int,
    val participants: String?,
    val tagIds: String,
    val summaryTemplate: String,
    val cleanAudio: Boolean,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "")
    val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = createdAt
)
