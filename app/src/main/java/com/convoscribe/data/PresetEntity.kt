package com.convoscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Saved conversation options for a recurring meeting (issue #32), see
 * [Preset]. [titlePattern] may hold `{date}` and `{time}`;
 * [participants] is encoded by [ParticipantsCodec]; [tagIds] are tag
 * ids, one per line, and ids of deleted tags are skipped on read.
 */
@Entity(tableName = "presets")
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
    val createdAt: Long
)
