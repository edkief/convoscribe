package com.convoscribe.llm

/**
 * What live assist shows for one question (issue #50): the [text] of
 * the answer without its citations, and the places it [cites] among
 * the excerpts that were sent.
 */
data class AssistReply(val text: String, val cites: List<Citation>)

/**
 * Reads the model's reply to [PromptBuilder.liveAssist] (issue #50).
 * [PromptBuilder.NONE_ANSWER] (in any case, with punctuation, fences or
 * a reasoning block around it, or starting the reply) means there is
 * nothing to show, and so does an empty reply.
 */
object AssistAnswer {

    /** The longest answer a card shows; the model is asked for 2 sentences. */
    const val MAX_CHARS: Int = 400

    private val THINK = Regex("<think>.*?</think>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val FENCE = Regex("^```[a-zA-Z]*\\s*|\\s*```$")
    private val NONE = Regex("^\\W*${PromptBuilder.NONE_ANSWER}\\b", RegexOption.IGNORE_CASE)

    /** The answer in [reply], or `null` when there is nothing to show. */
    fun parse(reply: String, excerpts: CrossExcerpts): AssistReply? {
        var text = THINK.replace(reply, "")
        // A reasoning block whose start was cut off or never closed.
        val closing = text.lastIndexOf("</think>", ignoreCase = true)
        if (closing >= 0) text = text.substring(closing + "</think>".length)
        if (text.contains("<think>", ignoreCase = true)) return null
        text = FENCE.replace(text.trim(), "").trim()
        if (text.isEmpty() || NONE.containsMatchIn(text)) return null
        val cites = CitationParser.parse(text, excerpts)
        val shown = CitationParser.strip(text).replace(Regex("\\s*\\n\\s*"), " ").trim()
        if (shown.isEmpty()) return null
        return AssistReply(clip(shown), cites)
    }

    /** [text] cut to [MAX_CHARS] on a word, behind an ellipsis. */
    private fun clip(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val cut = text.substring(0, MAX_CHARS)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_CHARS / 2) cut.substring(0, space) else cut).trimEnd() + "…"
    }
}
