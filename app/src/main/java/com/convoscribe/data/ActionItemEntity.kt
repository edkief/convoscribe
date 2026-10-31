package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * An action item of a session's summary (issue #31): [text] to be done
 * by [owner] (a speaker or participant name when it could be matched),
 * by [dueDate] (`yyyy-MM-dd`), agreed at [atMs] on the recording's
 * clock; each `null` when the summary did not say.
 *
 * [extractedText] is the text as the summary gave it, kept when the
 * user edits [text] so a regenerated summary still recognises the
 * item. [done], [edited] and [deleted] are the user's: an item with
 * any of them set survives regenerating the summary
 * ([ActionItemMerge]). A deleted item is only hidden, so the next
 * summary does not bring it back. [position] orders the items as the
 * summary listed them. Like the marks they go with their session
 * (`onDelete = CASCADE`).
 */
@Entity(
    tableName = "action_items",
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
data class ActionItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val text: String,
    val extractedText: String,
    val owner: String? = null,
    val dueDate: String? = null,
    val atMs: Long? = null,
    @ColumnInfo(defaultValue = "0")
    val done: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val edited: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val deleted: Boolean = false,
    val position: Int,
    val createdAt: Long
) {
    /** Whether the user did anything to it, so regenerating keeps it. */
    val touched: Boolean get() = done || edited || deleted
}

/** An open action item with the session it belongs to, for *Open actions* (issue #31). */
data class OpenActionItem(
    @Embedded val item: ActionItemEntity,
    val sessionTitle: String,
    val sessionCreatedAt: Long
)
