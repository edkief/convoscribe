package com.convoscribe.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A question or answer in *Ask across sessions* (issue #49). Unlike
 * [ChatMessageEntity] it belongs to no session: deleting a session
 * keeps the answers that cite it, and their citations then show the
 * session as deleted.
 *
 * [filters] is the [SearchFilters] a USER question was asked with and
 * [citations] the places an ASSISTANT answer cites, both as JSON
 * ([GlobalChatCodec]); [excerptCount] and [sessionCount] say what an
 * answer was based on, and [semantic] whether meaning counted as well
 * as words (issue #61). All `null` where they do not apply.
 */
@Entity(tableName = "global_chat_messages")
data class GlobalChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    val filters: String? = null,
    val citations: String? = null,
    val excerptCount: Int? = null,
    val sessionCount: Int? = null,
    val semantic: Boolean? = null
)
