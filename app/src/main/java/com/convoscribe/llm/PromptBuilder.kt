package com.convoscribe.llm

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
 * Long transcripts are truncated to a caller-supplied character
 * budget with [TranscriptTruncator.fit] before they are embedded
 * in the prompt, so a 60-minute conversation does not blow the
 * model's context window. The view-model picks the budget from
 * the chat model's documented context size minus the headroom
 * reserved for the answer.
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
     * Build the messages list for a one-shot summary call.
     *
     * Returns two messages: the system prompt for the template
     * [body] (see [summarySystem]; Meeting by default), then a
     * single user turn whose body is `Transcript:\n\n` followed by
     * the transcript truncated to [maxChars] characters. The model
     * sees the structured instructions first and the raw transcript
     * second, which is the order OpenAI's chat format recommends.
     */
    fun summary(transcript: String, maxChars: Int, body: String = SummaryTemplates.MEETING_BODY): List<LlmMessage> =
        listOf(
            LlmMessage(LlmMessage.ROLE_SYSTEM, summarySystem(body)),
            LlmMessage(
                LlmMessage.ROLE_USER,
                "Transcript:\n\n" + TranscriptTruncator.fit(transcript, maxChars)
            )
        )

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
    fun question(transcript: String, maxChars: Int, history: List<LlmMessage>, question: String): List<LlmMessage> {
        val systemMessage: LlmMessage = LlmMessage(
            LlmMessage.ROLE_SYSTEM,
            QA_SYSTEM_PREFIX + TranscriptTruncator.fit(transcript, maxChars)
        )
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
