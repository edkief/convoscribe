package com.convoscribe.llm

import com.convoscribe.data.SessionRepository

/**
 * Turns the model's answer to [PromptBuilder.title] into a session
 * title (issue #32): the first non-blank line, without a "Title:"
 * label, Markdown, surrounding quotes or a trailing full stop, at most
 * [MAX_WORDS] words and [SessionRepository.SESSION_TITLE_MAX_LEN]
 * characters. `null` when nothing usable is left.
 */
object TitleCleaner {
    const val MAX_WORDS: Int = 6

    private val LABEL = Regex("^(title|titre)\\s*:\\s*", RegexOption.IGNORE_CASE)
    private val MARKUP = Regex("^[#>*_`\\-\\s]+|[*_`]+")
    private const val QUOTES = "\"'“”‘’«»`"
    private const val TRAILING = ".,;:!?-–— "

    fun clean(raw: String): String? {
        var line = raw.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return null
        line = MARKUP.replace(line, "")
        line = LABEL.replace(line, "")
        line = line.trim { it.isWhitespace() || it in QUOTES }
        val words = line.split(Regex("\\s+")).filter { it.isNotEmpty() }.take(MAX_WORDS)
        val title = words.joinToString(" ")
            .take(SessionRepository.SESSION_TITLE_MAX_LEN)
            .trimEnd { it in TRAILING || it in QUOTES }
            .trim()
        return title.takeIf { it.any(Char::isLetterOrDigit) }
    }
}
