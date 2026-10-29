package com.convoscribe.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * A speaker enrolled by the user in v2: a name plus the
 * speaker-embedding vector (a [ByteArray] of little-endian
 * float32, see [EmbeddingCodec]) the diarizer emits for a
 * short voice sample. Enrolled rows are global — they are not
 * scoped to a session, so the same enrollment is reused across
 * every recording.
 *
 * The unique index on [name] is what makes
 * [EnrolledSpeakerDao.upsertByName] safe: saving the same
 * `name` twice replaces the existing row's [embedding] /
 * [createdAt] without producing duplicates.
 */
@Entity(
    tableName = "enrolled_speakers",
    // Not unique since schema v3: a person may have several voice
    // samples (one row each).
    indices = [Index(value = ["name"]), Index(value = ["syncId"], unique = true)]
)
data class EnrolledSpeakerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val embedding: ByteArray,
    val createdAt: Long,
    /**
     * Globally unique id (a UUID) of this voice sample (issue #70), so
     * sync matches devices' copies; samples from before v19 got a
     * random one on upgrade ([MIGRATION_18_19]). A sample is never
     * edited, so it has no `updatedAt`.
     */
    @ColumnInfo(defaultValue = "")
    val syncId: String = UUID.randomUUID().toString()
) {
    /**
     * [ByteArray] equality is reference-based, which would make
     * Room emit spurious "row changed" notifications when the
     * embedding is reassigned. We compare contents instead so
     * two rows with the same name + same floats collapse to
     * `equals` and a replacement shows up as a real change.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EnrolledSpeakerEntity) return false
        if (id != other.id) return false
        if (name != other.name) return false
        if (!embedding.contentEquals(other.embedding)) return false
        if (createdAt != other.createdAt) return false
        if (syncId != other.syncId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + syncId.hashCode()
        return result
    }
}
