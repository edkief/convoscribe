package com.convoscribe.llm

import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark

/**
 * Builds the exact list of [LlmMessage]s the app sends to the LLM
 * for the two operations that need a transcript in context:
 *
 *  - [summary] — a one-shot pass over the whole transcript that
 *    returns a write-up shaped by a [SummaryTemplate] (Meeting by
 *    default: Overview / Key points / Decisions / Action items);
 *  - [question] — a multi-round chat where the user asks follow-up
 *    questions and the model answers them from the same transcript.
 *
 * The caller passes the character budget for the transcript, the
 * "Max transcript characters" setting. A transcript within it goes
 * into [summary] or [question] whole. A longer one (issue #28) is
 * summarised in parts — [summaryPart] for each chunk, [mergeNotes]
 * while the notes are still too long, then [summaryFromParts] — and
 * questions about it are answered from the most relevant parts with
 * [questionFromExcerpts].
 */
object PromptBuilder {

    /**
     * Opening of every summary system prompt (issue #27): what the
     * model does and that it writes in the transcript's language.
     * [summarySystem] puts the template body after it.
     */
    const val SUMMARY_PREAMBLE: String =
        "You summarise conversation transcripts. Write in the same language as the transcript. "

    /**
     * Closing of every summary system prompt, after the template
     * body, so no template — built-in or user-written — can drop it.
     */
    const val SUMMARY_GUARDRAILS: String =
        "Use the speaker names exactly as they appear. Do not invent facts that are not in the transcript."

    /**
     * System prompt for a one-shot summary with the Meeting template:
     * a fixed structure (Overview → Key points → Decisions → Action
     * items), framed by [SUMMARY_PREAMBLE] and [SUMMARY_GUARDRAILS].
     * The literal text is part of the contract with the model:
     * changing it changes what the user sees on the Summary screen.
     */
    val SUMMARY_SYSTEM: String = summarySystem(SummaryTemplates.MEETING_BODY)

    /**
     * The summary system prompt for a template [body]:
     * [SUMMARY_PREAMBLE], the body trimmed and ended with a newline,
     * then [SUMMARY_GUARDRAILS]. A blank body leaves just the two.
     */
    fun summarySystem(body: String): String {
        val trimmed = body.trim()
        val framed = if (trimmed.isEmpty()) "" else trimmed + "\n"
        return SUMMARY_PREAMBLE + framed + SUMMARY_GUARDRAILS
    }

    /**
     * System prompt prefix for a Q&A turn. The transcript is
     * appended directly after the `\nTranscript:\n` literal so the
     * model has the conversation in its system context — that way
     * any per-question user turn is short and we can keep the last
     * few exchanges of chat history too without blowing the budget.
     */
    const val QA_SYSTEM_PREFIX: String =
        "You answer questions about the conversation transcript below. " +
            "Answer only from the transcript; if the answer is not in it, " +
            "say that it is not in the transcript. " +
            "Answer in the language of the question. " +
            "Cite timestamps like [12:34] when useful.\n\nTranscript:\n"

    /**
     * Opening of the list of marked moments (issue #30) in a summary's
     * user turn; one `[mm:ss] note` line per mark follows.
     */
    const val MARKS_HEADER: String =
        "The user marked these moments as important while recording. " +
            "Give them priority in the summary:\n"

    /** Stands in for the note of a mark that has none. */
    const val MARK_WITHOUT_NOTE: String = "(no note)"

    /**
     * Build the messages list for a one-shot summary call.
     *
     * Returns two messages: the system prompt for the template
     * [body] (see [summarySystem]; Meeting by default), then a
     * single user turn whose body is `Transcript:\n\n` followed by
     * the transcript truncated to [maxChars] characters. The model
     * sees the structured instructions first and the raw transcript
     * second, which is the order OpenAI's chat format recommends.
     *
     * With [marks] (issue #30) the user turn opens with
     * [MARKS_HEADER] and a `[mm:ss] note` line per mark, then a blank
     * line, before `Transcript:`. They come first so truncating a long
     * transcript never drops them, and the system prompt is unchanged.
     */
    fun summary(
        transcript: String,
        maxChars: Int,
        body: String = SummaryTemplates.MEETING_BODY,
        marks: List<TranscriptMark> = emptyList()
    ): List<LlmMessage> = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, summarySystem(body)),
        LlmMessage(
            LlmMessage.ROLE_USER,
            marksSection(marks) + "Transcript:\n\n" + TranscriptTruncator.fit(transcript, maxChars)
        )
    )

    /**
     * System prompt for the notes on one part of a long transcript
     * (issue #28). The notes are input to the final summary, not shown
     * to the user, so they keep everything and every timestamp.
     */
    const val PART_NOTES_SYSTEM: String =
        "You take notes on one part of a long conversation transcript that is summarised in parts. " +
            "Write in the same language as the transcript. " +
            "Note every topic discussed, the key points, decisions, action items with who owns them, " +
            "open questions and notable quotes, each with its timestamp like [12:34]. " +
            "Be concise but leave nothing out, and write no introduction or conclusion. " +
            SUMMARY_GUARDRAILS

    /** System prompt for combining the notes on consecutive parts (issue #28). */
    const val MERGE_NOTES_SYSTEM: String =
        "You combine notes taken on consecutive parts of a long conversation into one set of notes. " +
            "Write in the same language as the notes. " +
            "Keep every topic, key point, decision, action item with its owner, open question and quote, " +
            "with their timestamps like [12:34]; merge what is repeated and drop nothing else. " +
            SUMMARY_GUARDRAILS

    /**
     * Opening of the final summary's notes (issue #28); `%d` is the
     * number of parts.
     */
    const val PARTS_INTRO_FORMAT: String =
        "The transcript was too long to send at once, so notes were taken on it in %d parts. " +
            "Write the summary of the whole conversation from these notes, which are in time order:\n\n"

    /**
     * Messages for the notes on part [part] of [parts] of a long
     * transcript: [PART_NOTES_SYSTEM], then a user turn with the
     * [marks] in this part (as in [summary]) and the [chunk] headed
     * by its number and time span.
     */
    fun summaryPart(
        chunk: TranscriptChunk,
        part: Int,
        parts: Int,
        marks: List<TranscriptMark> = emptyList()
    ): List<LlmMessage> = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, PART_NOTES_SYSTEM),
        LlmMessage(
            LlmMessage.ROLE_USER,
            marksSection(marks) +
                "Transcript, part $part of $parts ${span(chunk.startMs, chunk.endMs)}:\n\n" + chunk.text
        )
    )

    /** Messages that combine [notes] on consecutive parts into one set: [MERGE_NOTES_SYSTEM], then the notes. */
    fun mergeNotes(notes: List<PartNotes>): List<LlmMessage> = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, MERGE_NOTES_SYSTEM),
        LlmMessage(LlmMessage.ROLE_USER, "Notes, in time order:\n\n" + notesText(notes))
    )

    /**
     * Messages for the summary of a long transcript from the [notes]
     * on its parts: the template's system prompt as in [summary], then
     * a user turn with all the [marks], [PARTS_INTRO_FORMAT] and the
     * notes. The notes are fitted to [maxChars] with
     * [TranscriptTruncator.fit], which leaves them as they are unless
     * combining them failed to make them short enough.
     */
    fun summaryFromParts(
        notes: List<PartNotes>,
        maxChars: Int,
        body: String = SummaryTemplates.MEETING_BODY,
        marks: List<TranscriptMark> = emptyList()
    ): List<LlmMessage> = listOf(
        LlmMessage(LlmMessage.ROLE_SYSTEM, summarySystem(body)),
        LlmMessage(
            LlmMessage.ROLE_USER,
            marksSection(marks) + PARTS_INTRO_FORMAT.format(notes.size) +
                TranscriptTruncator.fit(notesText(notes), maxChars)
        )
    )

    /** [notes] as `Part i [a–b]:` blocks separated by blank lines; what [mergeNotes] and [summaryFromParts] send. */
    fun notesText(notes: List<PartNotes>): String = notes.withIndex().joinToString(separator = "\n\n") { (i, n) ->
        "Part ${i + 1} ${span(n.startMs, n.endMs)}:\n${n.text.trim()}"
    }

    private fun span(startMs: Long, endMs: Long): String =
        "[${TranscriptFormatter.timestamp(startMs)}–${TranscriptFormatter.timestamp(endMs)}]"

    /** [MARKS_HEADER] and one line per mark in time order, then a blank line; empty without marks. */
    private fun marksSection(marks: List<TranscriptMark>): String {
        if (marks.isEmpty()) return ""
        return marks.sortedBy { it.atMs }.joinToString(separator = "", prefix = MARKS_HEADER, postfix = "\n") {
            val note = it.note?.takeIf { n -> n.isNotBlank() } ?: MARK_WITHOUT_NOTE
            "[${TranscriptFormatter.timestamp(it.atMs)}] $note\n"
        }
    }

    /**
     * System prompt for the full note of one mark (issue #48): the
     * user's short note says what they cared about, the transcript
     * around the mark says what was said.
     */
    const val MARK_NOTE_SYSTEM: String =
        "You turn a short note the user jotted while recording a conversation into a full note, " +
            "using the part of the transcript around the moment they marked. " +
            "Write in the same language as the transcript. " +
            "Write 1 to 4 sentences or short bullet points about what was said at that moment, " +
            "focused on what the user's note points at, with no heading or introduction. " +
            SUMMARY_GUARDRAILS

    /** Stands in for the note of a mark that has none, in [markNote]. */
    const val MARK_NOTE_WITHOUT_NOTE: String =
        "The user left no note: say what was being discussed at that moment."

    /**
     * Messages for the full note of [mark] (issue #48): [MARK_NOTE_SYSTEM],
     * then a user turn with the mark's time and note (or
     * [MARK_NOTE_WITHOUT_NOTE]) and the [window] of transcript around
     * it ([MarkWindow]), fitted to [maxChars].
     */
    fun markNote(mark: TranscriptMark, window: List<TranscriptLine>, maxChars: Int): List<LlmMessage> {
        val note = mark.note?.takeIf { it.isNotBlank() }
        val ask = if (note == null) MARK_NOTE_WITHOUT_NOTE else "The user's note: \"$note\""
        return listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, MARK_NOTE_SYSTEM),
            LlmMessage(
                LlmMessage.ROLE_USER,
                "Marked at [${TranscriptFormatter.timestamp(mark.atMs)}]. $ask\n\n" +
                    "Transcript around it:\n\n" +
                    TranscriptTruncator.fit(TranscriptFormatter.toText(window), maxChars)
            )
        )
    }

    /**
     * System prompt for a session title (issue #32); [title] adds the
     * language. Kept short: the answer is a few words.
     */
    const val TITLE_SYSTEM: String =
        "You name recorded conversations. Reply with a title of at most 6 words that tells this " +
            "conversation apart from others: its main topic, not the kind of meeting. " +
            "No quotes, no punctuation at the end, nothing else."

    /** How much of the summary goes into a title request; the opening is what matters. */
    const val TITLE_SUMMARY_MAX_CHARS: Int = 4_000

    /**
     * Messages for a session title (issue #32) from its saved
     * [summary], in [language] (`"fr"` French, anything else English).
     */
    fun title(summary: String, language: String): List<LlmMessage> {
        val name = if (language == "fr") "French" else "English"
        return listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, "$TITLE_SYSTEM Write it in $name."),
            LlmMessage(LlmMessage.ROLE_USER, "Summary:\n\n" + summary.trim().take(TITLE_SUMMARY_MAX_CHARS))
        )
    }

    /**
     * Build the messages list for a Q&A turn about a transcript.
     *
     * The list is:
     *  1. a single system message whose body is [QA_SYSTEM_PREFIX]
     *     followed by the transcript truncated to [maxChars];
     *  2. up to the last six messages of [history] that are
     *     user/assistant turns (any other role — e.g. a future
     *     "tool" turn — is filtered out before truncation);
     *  3. a single user turn with the trimmed [question].
     *
     * The trimming of the question is done before it is wrapped so
     * the model never sees leading/trailing whitespace that would
     * eat tokens in the prompt.
     */
    fun question(transcript: String, maxChars: Int, history: List<LlmMessage>, question: String): List<LlmMessage> =
        qa(QA_SYSTEM_PREFIX + TranscriptTruncator.fit(transcript, maxChars), history, question)

    /**
     * Opening of the system prompt for a question about a transcript
     * too long to send whole (issue #28); the [Excerpts] follow it.
     */
    const val QA_EXCERPTS_PREFIX: String =
        "You answer questions about a conversation. The transcript is too long to include, " +
            "so below are only the parts of it most relevant to the question, in time order; " +
            "[…] marks a gap between parts. Answer only from these parts; if the answer is not in them, " +
            "say that it is not in the parts of the transcript you were given. " +
            "Answer in the language of the question. " +
            "Cite timestamps like [12:34] when useful.\n\nTranscript excerpts:\n"

    /** Between two excerpts that are not next to each other in the transcript. */
    const val EXCERPT_GAP: String = "\n[…]\n"

    /**
     * Build the messages for a question about a long transcript
     * (issue #28): as [question], with the system prompt
     * [QA_EXCERPTS_PREFIX] followed by the [excerpts] in time order,
     * consecutive chunks joined by a newline and the others by
     * [EXCERPT_GAP].
     */
    fun questionFromExcerpts(excerpts: Excerpts, history: List<LlmMessage>, question: String): List<LlmMessage> {
        val text = StringBuilder()
        excerpts.chunks.forEachIndexed { index, chunk ->
            if (index > 0) {
                val previous = excerpts.chunks[index - 1]
                text.append(if (chunk.firstLine <= previous.lastLine + 1) "\n" else EXCERPT_GAP)
            }
            text.append(chunk.text)
        }
        return qa(QA_EXCERPTS_PREFIX + text, history, question)
    }

    /** [system], the recent user/assistant turns of [history], then the trimmed [question]. */
    private fun qa(system: String, history: List<LlmMessage>, question: String): List<LlmMessage> {
        val systemMessage: LlmMessage = LlmMessage(LlmMessage.ROLE_SYSTEM, system)
        val recentHistory: List<LlmMessage> = history
            .filter {
                it.role == LlmMessage.ROLE_USER ||
                    it.role == LlmMessage.ROLE_ASSISTANT
            }
            .takeLast(HISTORY_WINDOW)
        val questionMessage: LlmMessage = LlmMessage(
            LlmMessage.ROLE_USER,
            question.trim()
        )
        return listOf(systemMessage) + recentHistory + listOf(questionMessage)
    }

    /**
     * Number of past turns kept in [question]'s history window.
     * Six messages is three full user/assistant exchanges, which is
     * enough context for "what did we decide about X?" follow-ups
     * while keeping the prompt comfortably under most model context
     * limits.
     */
    private const val HISTORY_WINDOW: Int = 6
}
