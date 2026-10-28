package com.convoscribe.asr.server

import com.convoscribe.asr.TextNormalizer
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriptEvent
import kotlin.math.roundToLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns an OpenAI-style `verbose_json` transcription into transcript
 * lines (issue #58).
 *
 * Each `segments[]` entry becomes one [TranscriptEvent.Final] with its
 * `start` / `end` (seconds) in milliseconds and the text through
 * [TextNormalizer]. Its tokens are the `words[]` that start in the
 * segment, each as `" word"` so [TimedToken.isWordStart] holds; they
 * let speaker identification split a line at a speaker change. A word
 * belongs to the last segment starting at or before it (the first
 * segment for a word before them all), so none is lost to a gap in
 * the server's timings. A server without word timestamps gives lines
 * without tokens.
 *
 * A body with no segments but a non-blank `text` (plain `json`)
 * becomes one line over the whole `duration`. Blank lines are dropped.
 */
object ServerResponseParser {

    /**
     * Parse [body]. Throws [ServerAsrException] when it is not a JSON
     * object (an HTML error page from a proxy, a wrong URL).
     */
    fun parse(body: String): List<TranscriptEvent.Final> {
        val root = try {
            Json.parseToJsonElement(body).jsonObject
        } catch (e: IllegalArgumentException) {
            // Covers kotlinx.serialization's SerializationException and
            // the non-object `jsonObject` cast.
            throw ServerAsrException(
                "Unexpected response from the server (not a transcription): ${body.take(200)}",
                cause = e
            )
        }
        val words = (root["words"] as? JsonArray).orEmpty().mapNotNull { element ->
            val word = element as? JsonObject ?: return@mapNotNull null
            val text = word.string("word")?.trim()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val start = word.millis("start") ?: return@mapNotNull null
            TimedToken(" $text", start)
        }
        val segments = (root["segments"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (segments.isEmpty()) {
            val text = TextNormalizer.normalize(root.string("text").orEmpty())
            if (text.isBlank()) return emptyList()
            val endMs = root.millis("duration") ?: words.lastOrNull()?.ms ?: 0L
            return listOf(TranscriptEvent.Final(text, 0L, endMs, words))
        }
        val starts = segments.map { it.millis("start") ?: 0L }
        val wordsBySegment = words.groupBy { word ->
            starts.indexOfLast { it <= word.ms }.coerceAtLeast(0)
        }
        return segments.mapIndexedNotNull { i, segment ->
            val text = TextNormalizer.normalize(segment.string("text").orEmpty())
            if (text.isBlank()) return@mapIndexedNotNull null
            val startMs = starts[i]
            val endMs = maxOf(segment.millis("end") ?: startMs, startMs)
            TranscriptEvent.Final(text, startMs, endMs, wordsBySegment[i].orEmpty())
        }
    }

    private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    /** A number of seconds under [key], in milliseconds. */
    private fun JsonObject.millis(key: String): Long? =
        (get(key) as? JsonPrimitive)?.doubleOrNull?.let { (it * 1_000).roundToLong() }
}
