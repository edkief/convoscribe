package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.decision.DecisionAnswer
import com.convoscribe.decision.DecisionQuestion

/**
 * How a talking-point check asks a laya decision service (issue #101),
 * in place of the language model's prompt ([PromptBuilder.talkingPoints]).
 *
 * laya answers typed questions about a short text in one pass, and
 * reads only about 320 tokens of it with its English model, so asking
 * about a whole stretch of transcript at once does not work (measured
 * near chance). Instead every transcript line is a state of its own
 * ([states]), and the open points are put to it as `choice` questions
 * of [GROUP_SIZE] points each, plus a "none of these" option
 * ([questions]). A point is covered at the first line where it is the
 * answer with a probability of at least [MIN_PROBABILITY] ([covered]),
 * which also gives the time it came up.
 *
 * A question with only one or two points makes laya pick them far too
 * often (measured: 8 of 17 points not discussed were ticked with one
 * point per question, 2 with five). So the open points are spread
 * evenly over the questions, and each question is filled up to
 * [GROUP_SIZE] with the session's other points, those already ticked:
 * real topics of the conversation, which are never ticked again.
 * Made-up filler options did worse.
 */
object DecisionTalkingPoints {

    /** The question asked of each line. */
    const val INSTRUCTIONS: String = "Which talking point is this part of the conversation about?"

    /** The option a line takes when it is about none of the points. */
    const val NONE_KEY: String = "none"
    const val NONE_TEXT: String = "none of these: something else, small talk or logistics"

    /**
     * Points per question: enough that a point is never the only real
     * option, few enough for laya's small option budget.
     */
    const val GROUP_SIZE: Int = 5

    /** How sure the answer must be for a point to count as covered. */
    const val MIN_PROBABILITY: Double = 0.5

    /** The longest state sent; longer lines go in parts, so nothing is cut off. */
    const val MAX_STATE_CHARS: Int = 1_000

    /** One state sent: a line, or part of a long one, and when that line starts. */
    data class State(val text: String, val atMs: Long)

    /** The states for [lines], in order: one per line, or several for a line over [MAX_STATE_CHARS]. */
    fun states(lines: List<TranscriptLine>): List<State> = lines.flatMap { line ->
        val whole = TranscriptFormatter.lineText(line)
        if (whole.length <= MAX_STATE_CHARS) {
            listOf(State(whole, line.startMs))
        } else {
            val room = MAX_STATE_CHARS - (whole.length - line.text.length)
            split(line.text, room.coerceAtLeast(MAX_STATE_CHARS / 2)).map { part ->
                State(TranscriptFormatter.lineText(line.copy(text = part)), line.startMs)
            }
        }
    }

    /**
     * Questions about the [open] points (numbered from 1, in order),
     * keyed by question id. The open points are spread evenly over as
     * few questions as hold [GROUP_SIZE] each, every question is filled
     * up to [GROUP_SIZE] with [others] (the session's points that are not
     * open, numbered after the open ones, taken in turn), and each ends
     * with [NONE_KEY]. Only numbers up to `open.size` can be ticked.
     */
    fun questions(open: List<String>, others: List<String> = emptyList()): Map<String, DecisionQuestion> {
        if (open.isEmpty()) return emptyMap()
        val groups = (open.size + GROUP_SIZE - 1) / GROUP_SIZE
        var nextOther = 0
        return (0 until groups).associate { group ->
            val options = LinkedHashMap<String, String>()
            // Point i goes to group i % groups: sizes differ by one at most.
            for (i in open.indices) if (i % groups == group) options["${i + 1}"] = open[i]
            val fill = minOf(GROUP_SIZE - options.size, others.size)
            repeat(fill) {
                val j = nextOther++ % others.size
                options["${open.size + j + 1}"] = others[j]
            }
            options[NONE_KEY] = NONE_TEXT
            "points${group + 1}" to DecisionQuestion(INSTRUCTIONS, options)
        }
    }

    /**
     * The points the [answers] (one map per state of [states]) say were
     * covered, of [count] asked about: each at the first state where it
     * was the answer with at least [MIN_PROBABILITY].
     */
    fun covered(states: List<State>, answers: List<Map<String, DecisionAnswer>>, count: Int): List<CoveredPoint> {
        val first = LinkedHashMap<Int, Long>()
        for ((state, byQuestion) in states.zip(answers)) {
            for (answer in byQuestion.values) {
                val number = answer.choice.toIntOrNull() ?: continue
                if (number !in 1..count || number in first) continue
                if (answer.probabilityOf(answer.choice) >= MIN_PROBABILITY) first[number] = state.atMs
            }
        }
        return first.entries.sortedBy { it.key }.map { (number, atMs) -> CoveredPoint(number, atMs) }
    }

    /** [text] in parts of at most [max] characters, broken after a sentence or at a space where possible. */
    internal fun split(text: String, max: Int): List<String> {
        val parts = mutableListOf<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max + 1)
            val sentence = SENTENCE_END.findAll(window).lastOrNull()?.range?.first?.plus(1)
            val cut = sentence?.takeIf { it > max / 2 }
                ?: window.lastIndexOf(' ').takeIf { it > max / 2 }
                ?: max
            parts += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) parts += rest
        return parts
    }

    private val SENTENCE_END = Regex("[.!?…](?=\\s)")
}
