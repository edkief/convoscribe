package com.convoscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A summary template the user wrote (issue #27): a [name] and the
 * [body] of instructions placed between the fixed preamble and
 * guardrails (see [com.convoscribe.llm.PromptBuilder.summarySystem]).
 * Built-in templates are not stored; they live in
 * [com.convoscribe.llm.SummaryTemplates].
 */
@Entity(tableName = "summary_templates")
data class SummaryTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val body: String,
    val createdAt: Long
)
