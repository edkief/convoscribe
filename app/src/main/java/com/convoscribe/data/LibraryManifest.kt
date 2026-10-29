package com.convoscribe.data

import java.security.MessageDigest
import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * A known-voice sample as sync sends it (issue #70): [embedding] is
 * [EmbeddingCodec]'s bytes in Base64. A sample is never edited, only
 * added and deleted.
 */
@Serializable
data class VoiceManifest(
    val schema: Int = LibraryManifests.SCHEMA,
    val syncId: String,
    val name: String,
    val embedding: String,
    val createdAt: Long
)

/** A summary template the user wrote, as sync sends it (issue #70). */
@Serializable
data class TemplateManifest(
    val schema: Int = LibraryManifests.SCHEMA,
    val syncId: String,
    val name: String,
    val body: String,
    val createdAt: Long
)

/**
 * A preset as sync sends it (issue #70): its tags by name, sorted, and
 * its template by [SessionManifest.TemplateRef], since both are local
 * ids here.
 */
@Serializable
data class PresetManifest(
    val schema: Int = LibraryManifests.SCHEMA,
    val syncId: String,
    val name: String,
    val titlePattern: String = "",
    val language: String,
    val numSpeakers: Int = -1,
    val participants: List<String>? = null,
    val tags: List<String> = emptyList(),
    val summaryTemplate: SessionManifest.TemplateRef? = null,
    val cleanAudio: Boolean = false,
    val createdAt: Long
)

/**
 * Encoding and hashing the library's manifests (issue #70), the same
 * canonical way as [SessionManifests], so an unchanged item always
 * hashes the same.
 */
object LibraryManifests {
    /** Version of the library manifests; a reader refuses a newer one. */
    const val SCHEMA: Int = 1

    fun <M> encode(serializer: KSerializer<M>, manifest: M): String =
        SessionManifests.json.encodeToString(serializer, manifest)

    fun <M> decode(serializer: KSerializer<M>, text: String): M =
        SessionManifests.json.decodeFromString(serializer, text)

    /** SHA-256 of [manifest]'s canonical encoding, lower-case hex. */
    fun <M> hash(serializer: KSerializer<M>, manifest: M): String =
        sha256(encode(serializer, manifest).toByteArray(Charsets.UTF_8))

    fun voice(row: EnrolledSpeakerEntity): VoiceManifest = VoiceManifest(
        syncId = row.syncId,
        name = row.name,
        embedding = Base64.getEncoder().encodeToString(row.embedding),
        createdAt = row.createdAt
    )

    fun template(row: SummaryTemplateEntity): TemplateManifest =
        TemplateManifest(syncId = row.syncId, name = row.name, body = row.body, createdAt = row.createdAt)

    /** [row] with its tags named by [tagNames] and its template by [userTemplateNames]. */
    fun preset(row: PresetEntity, tagNames: Map<Long, String>, userTemplateNames: Map<Long, String>): PresetManifest =
        PresetManifest(
            syncId = row.syncId,
            name = row.name,
            titlePattern = row.titlePattern,
            language = row.language,
            numSpeakers = row.numSpeakers,
            participants = ParticipantsCodec.decode(row.participants),
            tags = row.tagIds.split('\n').mapNotNull { it.toLongOrNull()?.let(tagNames::get) }.sortedBy(TagNames::key),
            summaryTemplate = SessionManifests.templateRef(row.summaryTemplate, userTemplateNames),
            cleanAudio = row.cleanAudio,
            createdAt = row.createdAt
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
