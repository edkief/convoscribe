package com.convoscribe.llm

/**
 * What a summary should look like (issue #27): a [name] shown to the
 * user and a [body] of instructions that [PromptBuilder.summarySystem]
 * wraps between the fixed preamble and guardrails, so a template can
 * change the structure but never lose "write in the transcript's
 * language", "keep speaker names" or "don't invent facts".
 *
 * @property key stable id: a built-in's key (see [SummaryTemplates]),
 *   or `user:<id>` for one the user wrote ([SummaryTemplates.userKey]).
 * @property builtIn built-ins can be duplicated but not edited or
 *   deleted.
 */
data class SummaryTemplate(
    val key: String,
    val name: String,
    val body: String,
    val builtIn: Boolean
)

/** The built-in templates, and how a template key resolves. */
object SummaryTemplates {

    const val MEETING_KEY: String = "meeting"
    const val INTERVIEW_KEY: String = "interview"
    const val LECTURE_KEY: String = "lecture"
    const val ONE_ON_ONE_KEY: String = "one_on_one"
    const val VISIT_KEY: String = "visit"
    const val BRIEF_KEY: String = "brief"

    private const val USER_PREFIX: String = "user:"

    /**
     * Meeting's body. Framed by [PromptBuilder.summarySystem] it gives
     * back exactly the summary prompt from before templates existed.
     */
    const val MEETING_BODY: String =
        "Use this structure:\n" +
            "1. Overview: one short paragraph.\n" +
            "2. Key points: bullet list.\n" +
            "3. Decisions: bullet list, or \"None\".\n" +
            "4. Action items: bullet list with the owner's name when known, or \"None\".\n"

    val MEETING: SummaryTemplate = SummaryTemplate(MEETING_KEY, "Meeting", MEETING_BODY, builtIn = true)

    /** Every built-in, Meeting first. */
    val BUILT_IN: List<SummaryTemplate> = listOf(
        MEETING,
        SummaryTemplate(
            INTERVIEW_KEY,
            "Interview",
            "The transcript is an interview. Use this structure:\n" +
                "1. Context: who is interviewing whom, and about what, in one or two sentences.\n" +
                "2. Questions and answers: for each main question, the question and a short summary of the answer.\n" +
                "3. Notable quotes: up to five short quotes, with the speaker's name, or \"None\".\n" +
                "4. Follow-ups: open questions or next steps, or \"None\".\n",
            builtIn = true
        ),
        SummaryTemplate(
            LECTURE_KEY,
            "Lecture / talk notes",
            "The transcript is a lecture or talk. Write study notes with this structure:\n" +
                "1. Topic: one sentence.\n" +
                "2. Main ideas: a heading per idea with bullet points under it, in the order they were presented.\n" +
                "3. Definitions and key terms: bullet list, or \"None\".\n" +
                "4. Examples: bullet list, or \"None\".\n" +
                "5. Questions from the audience: each with its answer, or \"None\".\n",
            builtIn = true
        ),
        SummaryTemplate(
            ONE_ON_ONE_KEY,
            "1:1",
            "The transcript is a one-to-one between two people, such as a manager and a report. Use this structure:\n" +
                "1. Topics discussed: bullet list.\n" +
                "2. Updates and progress: bullet list, or \"None\".\n" +
                "3. Concerns or blockers raised: bullet list, or \"None\".\n" +
                "4. Feedback given: bullet list, or \"None\".\n" +
                "5. Action items: bullet list with the owner's name when known, or \"None\".\n",
            builtIn = true
        ),
        SummaryTemplate(
            VISIT_KEY,
            "Client / patient visit",
            "The transcript is a visit between a professional and a client or patient. Use this structure:\n" +
                "1. Reason for the visit.\n" +
                "2. What the client or patient reported: bullet list.\n" +
                "3. What the professional observed or explained: bullet list.\n" +
                "4. Recommendations or plan agreed: bullet list, or \"None\".\n" +
                "5. Follow-up: next appointment, tasks or documents, or \"None\".\n" +
                "Only record what was said; do not add advice or a diagnosis of your own.\n",
            builtIn = true
        ),
        SummaryTemplate(
            BRIEF_KEY,
            "Brief",
            "Write only 3 to 5 bullet points covering the most important things said. No headings, no introduction.\n",
            builtIn = true
        )
    )

    /** The key a user template with database [id] is stored under. */
    fun userKey(id: Long): String = USER_PREFIX + id

    /** The database id in a `user:<id>` key, or `null` for any other key. */
    fun userId(key: String): Long? =
        if (key.startsWith(USER_PREFIX)) key.removePrefix(USER_PREFIX).toLongOrNull() else null

    /**
     * The template [key] names among the built-ins and [userTemplates],
     * or [MEETING] when [key] is `null`, unknown or a deleted user
     * template.
     */
    fun resolve(key: String?, userTemplates: List<SummaryTemplate>): SummaryTemplate =
        BUILT_IN.firstOrNull { it.key == key } ?: userTemplates.firstOrNull { it.key == key } ?: MEETING
}
