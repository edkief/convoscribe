package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A summary template the user wrote (issue #27): a [name] and the
 * [body] of instructions placed between the fixed preamble and
 * guardrails (see [com.convoscribe.llm.PromptBuilder.summarySystem]).
 * Built-in templates are not stored; they live in
 * [com.convoscribe.llm.SummaryTemplates].
 *
 * [syncId] names the template on every device (issue #70; a random
 * one on upgrade, [MIGRATION_18_19]) and [updatedAt] is when it last
 * changed, for sync's "most recent wins".
 */
@Entity(tableName = "summary_templates", indices = [Index(value = ["syncId"], unique = true)])
data class SummaryTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String,
    val createdAt: Long,
    @ColumnInfo(defaultValue = "")
    val syncId: String = UUID.randomUUID().toString(),
    @ColumnInfo(defaultValue = "0")
    val updatedAt: Long = createdAt
)
