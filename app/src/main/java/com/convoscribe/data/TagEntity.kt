package com.convoscribe.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A label the user puts on sessions to group them by project, client
 * or anything else (issue #32). [name] is shown as typed;
 * [nameKey] is its case-folded form ([TagNames.key]) and is unique,
 * so "Acme" and "acme" are one tag.
 */
@Entity(
    tableName = "tags",
    indices = [Index(value = ["nameKey"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameKey: String,
    val createdAt: Long
)

/** A tag as the app shows it. */
data class Tag(
    val id: Long,
    val name: String
)

fun TagEntity.toTag(): Tag = Tag(id = id, name = name)

/** A tag and how many sessions carry it, for Settings › Tags. */
data class TagUsage(
    val id: Long,
    val name: String,
    val sessionCount: Int
)

/** One session–tag link with the tag's name, for the session list. */
data class SessionTagName(
    val sessionId: Long,
    val tagId: Long,
    val name: String
)

/** How tag names are cleaned up and compared (issue #32). */
object TagNames {
    /** Longest tag name; tags are chips, not sentences. */
    const val MAX_LEN: Int = 30

    /**
     * [name] trimmed, inner whitespace collapsed and capped at
     * [MAX_LEN], or `null` when nothing is left.
     */
    fun normalise(name: String): String? =
        name.trim().replace(WHITESPACE, " ").take(MAX_LEN).trim().takeIf { it.isNotEmpty() }

    /** Case-insensitive identity of an already [normalise]d name. */
    fun key(name: String): String = name.lowercase(java.util.Locale.ROOT)

    /** [names] normalised, blanks dropped, case-insensitive duplicates removed (first spelling wins). */
    fun clean(names: List<String>): List<String> = names.mapNotNull { normalise(it) }.distinctBy { key(it) }

    private val WHITESPACE = Regex("\\s+")
}
