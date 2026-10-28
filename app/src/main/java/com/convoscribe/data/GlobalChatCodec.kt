package com.convoscribe.data

import com.convoscribe.llm.Citation
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Round-trips the JSON columns of [GlobalChatMessageEntity] (issue #49).
 * A value that does not parse (written by a later version, say) reads
 * as absent rather than failing the whole history.
 */
object GlobalChatCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val citationList = ListSerializer(Citation.serializer())

    fun encodeFilters(filters: SearchFilters): String = json.encodeToString(SearchFilters.serializer(), filters)

    fun decodeFilters(value: String?): SearchFilters? =
        value?.let { decode { json.decodeFromString(SearchFilters.serializer(), it) } }

    /** [citations] as JSON, or `null` for none. */
    fun encodeCitations(citations: List<Citation>): String? =
        citations.takeIf { it.isNotEmpty() }?.let { json.encodeToString(citationList, it) }

    fun decodeCitations(value: String?): List<Citation> =
        value?.let { decode { json.decodeFromString(citationList, it) } }.orEmpty()

    private inline fun <T> decode(block: () -> T): T? = try {
        block()
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
