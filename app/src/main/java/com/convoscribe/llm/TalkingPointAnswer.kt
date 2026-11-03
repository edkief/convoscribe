package com.convoscribe.llm

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** A talking point the model says was covered: its [number] in the prompt (from 1), and when, if it said. */
data class CoveredPoint(val number: Int, val atMs: Long? = null)

/**
 * Reads the model's answer to a talking-point check (issue #103). The
 * model is asked ([PromptBuilder.TALKING_POINTS_SYSTEM]) for a JSON
 * array such as `[{"point": 2, "at": "12:34"}]`, or `[]`. Read
 * leniently: a reasoning block or code fence around it, bare numbers
 * (`[2, 3]`), numbers given as strings, and prose before or after the
 * array are all fine. Numbers outside `1..count` and repeats are
 * dropped; an `at` that is no time is ignored.
 */
object TalkingPointAnswer {

    private val json = Json { isLenient = true }

    private val THINK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /**
     * The points [answer] says were covered, of [count] asked about;
     * `null` when it holds no readable array. An answer with no array
     * and no digit at all ("None of them.") means nothing was covered.
     */
    fun parse(answer: String, count: Int): List<CoveredPoint>? {
        var text = THINK.replace(answer, "")
        // A reasoning block whose start was cut off or never closed.
        val closing = text.lastIndexOf("</think>", ignoreCase = true)
        if (closing >= 0) text = text.substring(closing + "</think>".length)
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end < start) {
            return if (text.none(Char::isDigit) && !text.contains("<think>", ignoreCase = true)) emptyList() else null
        }
        val array = try {
            json.parseToJsonElement(text.substring(start, end + 1)) as? JsonArray
        } catch (_: IllegalArgumentException) {
            null
        } ?: return null
        val seen = mutableSetOf<Int>()
        return array.mapNotNull { element -> point(element) }
            .filter { it.number in 1..count && seen.add(it.number) }
    }

    private fun point(element: JsonElement): CoveredPoint? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()?.toIntOrNull()?.let { CoveredPoint(it) }
        is JsonObject -> {
            val number = (element["point"] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()
            val at = (element["at"] as? JsonPrimitive)?.contentOrNull?.let(ActionItemsParser::timeMs)
            number?.let { CoveredPoint(it, at) }
        }
        else -> null
    }
}
