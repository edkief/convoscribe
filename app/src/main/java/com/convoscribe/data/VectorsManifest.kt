package com.convoscribe.data

import java.util.Base64
import kotlinx.serialization.Serializable

/**
 * A session's semantic vectors as sync sends them (issue #70), under
 * the session's sync id: what model [modelId] made from passages whose
 * text hashes to [textHash]. A shortcut, never the source of truth: a
 * device keeps them only when its own model and text match.
 */
@Serializable
data class VectorsManifest(
    val schema: Int = LibraryManifests.SCHEMA,
    val syncId: String,
    val modelId: String,
    val textHash: String,
    val passages: List<Passage>
) {
    /** One passage: [vector] is the float32 bytes ([toBytes]) in Base64. */
    @Serializable
    data class Passage(val firstLine: Int, val lastLine: Int, val vector: String)

    /** The passages as rows; their session id is set when they are stored. */
    fun rows(): List<PassageVectorEntity> = passages.mapIndexed { i, passage ->
        PassageVectorEntity(
            0,
            i,
            passage.firstLine,
            passage.lastLine,
            modelId,
            Base64.getDecoder().decode(passage.vector)
        )
    }

    companion object {
        fun of(syncId: String, state: SessionIndexStateEntity, vectors: List<PassageVectorEntity>) = VectorsManifest(
            syncId = syncId,
            modelId = state.modelId,
            textHash = state.textHash,
            passages = vectors.map {
                Passage(it.firstLine, it.lastLine, Base64.getEncoder().encodeToString(it.vector))
            }
        )
    }
}
