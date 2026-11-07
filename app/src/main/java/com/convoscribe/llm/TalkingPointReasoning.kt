package com.convoscribe.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * How a talking-point check asks the language model for little
 * reasoning (issue #136). Live checks need an answer within seconds, and
 * a model that reasons at length is both slower and stricter about points
 * only partly covered. There is no portable switch: OpenAI's
 * `reasoning_effort` is ignored by many local models, each of which reads
 * its own chat-template variable instead, and OpenAI's hosted API refuses
 * fields it does not know. So:
 *
 *  - with the sync server's services, a check carries only [HEADER], and
 *    the server adds whatever its current model needs;
 *  - with the user's own endpoint, the user opts in, and a check carries
 *    [ownEndpointFields]: `reasoning_effort: "low"` plus their own extra
 *    fields.
 */
object TalkingPointReasoning {
    /** The header the sync server reads (value [LOW]); it is never passed to the model. */
    const val HEADER: String = "X-Convoscribe-Reasoning"

    const val LOW: String = "low"

    /**
     * [text] as extra request fields: an empty object when blank, `null`
     * when it is not a JSON object.
     */
    fun parseFields(text: String): JsonObject? {
        if (text.isBlank()) return JsonObject(emptyMap())
        return runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
    }

    /**
     * What a check sends to the user's own endpoint: `reasoning_effort:
     * "low"`, then [extra]'s fields on top; a `null` in [extra] removes
     * that field (a model that refuses `reasoning_effort`). Invalid
     * [extra] adds nothing.
     */
    fun ownEndpointFields(extra: String): JsonObject {
        val merged = linkedMapOf<String, JsonElement>("reasoning_effort" to JsonPrimitive(LOW))
        parseFields(extra)?.forEach { (key, value) ->
            if (value is JsonNull) merged.remove(key) else merged[key] = value
        }
        return JsonObject(merged)
    }
}
