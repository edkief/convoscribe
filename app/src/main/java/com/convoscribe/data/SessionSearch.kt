package com.convoscribe.data

import com.convoscribe.llm.embedding.SemanticMatches
import com.convoscribe.llm.embedding.SemanticPassage
import com.convoscribe.llm.embedding.SemanticRanking
import kotlinx.serialization.Serializable

/**
 * Narrows a search: [language] `null` is any, only sessions created
 * at or after [since] count, and [speaker] (a speaker name or
 * expected participant, compared ignoring case and accents) `null`
 * is anyone. With [tagIds] (issue #32) only sessions carrying at least
 * one of those tags count; empty is any. Serializable so a question
 * across sessions keeps the filters it was asked with (issue #49).
 */
@Serializable
data class SearchFilters(
    val language: String? = null,
    val since: Long = 0L,
    val speaker: String? = null,
    val tagIds: Set<Long> = emptySet()
)

/**
 * A matching transcript line: where it starts, who said it, and the
 * snippet. [byMeaning] (issue #63): the line starts a passage close to
 * the query in meaning, and [text] is an excerpt of that passage with
 * nothing highlighted.
 */
data class SearchLine(
    val segmentId: Long,
    val startMs: Long,
    val speaker: String?,
    val speakerIndex: Int?,
    val text: Highlighted,
    val byMeaning: Boolean = false
)

/**
 * One session's matches: its [title] (highlighted when it matched),
 * the speaker names that matched, an excerpt of the saved summary
 * when it matched, and the matching transcript [lines] in reading
 * order. [summaryByMeaning] (issue #63): the summary is shown because
 * it is close to the query in meaning, not because it shares a word.
 */
data class SearchResult(
    val session: SessionEntity,
    val title: Highlighted,
    val speakers: List<Highlighted>,
    val summary: Highlighted?,
    val lines: List<SearchLine>,
    val summaryByMeaning: Boolean = false
)

/**
 * A session a cross-session question may be answered from (issue #49):
 * its [speakers], latest saved [summary] if any, and how many of its
 * transcript lines matched the question ([hits], `0` when it was
 * picked without a match).
 */
data class Candidate(
    val session: SessionEntity,
    val speakers: List<SpeakerEntity>,
    val summary: String?,
    val hits: Int
)

/** What the filters can offer: languages and names found across sessions, and the tags. */
data class SearchOptions(
    val languages: List<String> = emptyList(),
    val speakers: List<String> = emptyList(),
    val tags: List<Tag> = emptyList()
)

/**
 * Cross-session search (issue #24). Transcript lines come from the
 * FTS4 index ([SearchDao.findSegments]); titles, speaker names and
 * the latest saved summary are matched here with [SearchText], so
 * every source ignores case and accents and matches word prefixes
 * the same way. Results are grouped per session, newest first.
 */
class SessionSearch(private val dao: SearchDao) {

    /**
     * Sessions matching every word of [query] and passing [filters],
     * newest first.
     *
     * With [semantic] (issue #63), the stored passages close to the
     * query in meaning count as well, within the same filters. A query
     * of up to [SHORT_QUERY_TERMS] words keeps the word matches first,
     * as they are, and lists the sessions found only by meaning after
     * them, closest first. A longer one orders sessions by
     * reciprocal-rank fusion of the word ranking (most matches first)
     * and the meaning ranking, and adds to each session the passages
     * found by meaning that hold no matching line.
     */
    suspend fun search(
        query: String,
        filters: SearchFilters = SearchFilters(),
        semantic: SemanticMatches? = null
    ): List<SearchResult> {
        val terms = SearchText.terms(query)
        if (terms.isEmpty()) return emptyList()
        val speakers = dao.speakers().groupBy { it.sessionId }
        val sessions = filtered(filters, speakers)
        if (sessions.isEmpty()) return emptyList()
        val allowed = sessions.mapTo(HashSet()) { it.id }
        val summaries = dao.summaries().associate { it.sessionId to it.content }
        val hits = dao.findSegments(
            match = SearchText.ftsQuery(terms),
            language = filters.language,
            since = filters.since,
            open = SearchText.MARK_OPEN,
            close = SearchText.MARK_CLOSE,
            limit = MAX_LINES
        ).filter { it.sessionId in allowed }.groupBy { it.sessionId }

        val words = sessions.mapNotNull { session ->
            val sessionSpeakers = speakers[session.id].orEmpty()
            val title = SearchText.match(session.title, terms)
            val speakerHits = sessionSpeakers.mapNotNull { SearchText.match(it.label, terms) }
            val summary = summaries[session.id]?.let { SearchText.excerpt(it, terms) }
            val lines = hits[session.id].orEmpty().map { hit ->
                val speaker = hit.speakerIndex?.let { index ->
                    sessionSpeakers.firstOrNull { it.speakerIndex == index }
                }
                SearchLine(
                    segmentId = hit.segmentId,
                    startMs = hit.startMs,
                    speaker = speaker?.label,
                    speakerIndex = speaker?.speakerIndex,
                    text = SearchText.parseMarked(hit.snippet)
                )
            }
            if (title == null && speakerHits.isEmpty() && summary == null && lines.isEmpty()) {
                null
            } else {
                SearchResult(
                    session = session,
                    title = title ?: Highlighted(session.title),
                    speakers = speakerHits,
                    summary = summary,
                    lines = lines
                )
            }
        }
        val close = semantic?.sessions.orEmpty().filter { it in allowed }
        if (semantic == null || close.isEmpty()) return words
        return withMeaning(words, terms.size <= SHORT_QUERY_TERMS, semantic, close, sessions, speakers, summaries)
    }

    /** [words] with what [semantic] found in the sessions [close] to the query, best first (see [search]). */
    private suspend fun withMeaning(
        words: List<SearchResult>,
        short: Boolean,
        semantic: SemanticMatches,
        close: List<Long>,
        sessions: List<SessionEntity>,
        speakers: Map<Long, List<SpeakerEntity>>,
        summaries: Map<Long, String>
    ): List<SearchResult> {
        val byId = sessions.associateBy { it.id }
        val found = words.mapTo(HashSet()) { it.session.id }
        val meaningOnly = close.filter { it !in found }.take(MAX_MEANING_SESSIONS).mapNotNull { id ->
            withMeaningHits(byId.getValue(id), null, semantic, speakers[id].orEmpty(), summaries[id])
        }
        if (short) return words + meaningOnly
        // Stable sort: sessions with as many matches stay newest first.
        val wordRanking = words.sortedByDescending { it.matchCount() }.map { it.session.id }
        val results = words.map { result ->
            val id = result.session.id
            withMeaningHits(result.session, result, semantic, speakers[id].orEmpty(), summaries[id]) ?: result
        }.associateBy { it.session.id } + meaningOnly.associateBy { it.session.id }
        return SemanticRanking.fuse(wordRanking, close).mapNotNull { results[it] }
    }

    /**
     * [base] (`null` when [session] matched no word) with [session]'s
     * passages close in meaning: at most [MEANING_LINES_PER_SESSION]
     * lines, each the first line of a passage holding no line [base]
     * already shows, and the summary when it did not match by words.
     * `null` when there is nothing to show. A passage past the end of
     * the transcript (vectors from before lines were deleted) is skipped.
     */
    private suspend fun withMeaningHits(
        session: SessionEntity,
        base: SearchResult?,
        semantic: SemanticMatches,
        speakers: List<SpeakerEntity>,
        summary: String?
    ): SearchResult? {
        val passages = semantic.passages(session.id)
        val transcript = passages.filter { it.firstLine >= 0 }
        val lines = if (transcript.isEmpty()) {
            emptyList()
        } else {
            TranscriptFormatter.lines(dao.segments(session.id), speakers)
        }
        val shown = base?.lines.orEmpty().mapTo(HashSet()) { it.segmentId }
        val meaningLines = transcript.mapNotNull { passage ->
            if (passage.firstLine >= lines.size) return@mapNotNull null
            val range = lines.subList(passage.firstLine, minOf(passage.lastLine, lines.lastIndex) + 1)
            if (range.any { it.id in shown }) return@mapNotNull null
            val first = range.first()
            SearchLine(
                segmentId = first.id,
                startMs = first.startMs,
                speaker = first.speaker,
                speakerIndex = first.speakerIndex,
                text = Highlighted(excerpt(range.joinToString(" ") { it.text })),
                byMeaning = true
            )
        }.take(MEANING_LINES_PER_SESSION)
        val summaryClose = passages.any { it.firstLine == SemanticPassage.SUMMARY_LINE }
        val meaningSummary = if (base?.summary == null && summaryClose) {
            summary?.let { Highlighted(excerpt(it)) }
        } else {
            null
        }
        if (meaningLines.isEmpty() && meaningSummary == null) return base
        return SearchResult(
            session = session,
            title = base?.title ?: Highlighted(session.title),
            speakers = base?.speakers.orEmpty(),
            summary = base?.summary ?: meaningSummary,
            // Stable sort: word matches keep their order among lines starting together.
            lines = (base?.lines.orEmpty() + meaningLines).sortedBy { it.startMs },
            summaryByMeaning = meaningSummary != null
        )
    }

    /** How many places matched by words: lines, speakers, the title and the summary. */
    private fun SearchResult.matchCount(): Int =
        lines.size + speakers.size + (if (title.ranges.isEmpty()) 0 else 1) + (if (summary == null) 0 else 1)

    /** The start of [text], cut at a word to about [MEANING_EXCERPT_CHARS] characters. */
    private fun excerpt(text: String): String {
        val flat = text.trim().replace(SPACES, " ")
        if (flat.length <= MEANING_EXCERPT_CHARS) return flat
        val cut = flat.lastIndexOf(' ', MEANING_EXCERPT_CHARS).takeIf { it > MEANING_EXCERPT_CHARS / 2 }
            ?: MEANING_EXCERPT_CHARS
        return flat.take(cut).trimEnd() + "…"
    }

    /**
     * Languages used by sessions (in first-seen order, newest session
     * first) and the names of their speakers and expected
     * participants, sorted, without the default "Speaker N" labels;
     * and the tags that are on at least one session.
     */
    suspend fun options(): SearchOptions {
        val sessions = dao.sessions()
        val speakers = dao.speakers()
        val names = speakers.map { it.label } +
            sessions.flatMap { ParticipantsCodec.decode(it.participants).orEmpty() }
        val defaults = speakers.map { defaultSpeakerLabel(it.speakerIndex) }.toSet()
        return SearchOptions(
            languages = sessions.map { it.language }.filter { it.isNotBlank() }.distinct(),
            speakers = names.map { it.trim() }
                .filter { it.isNotEmpty() && it !in defaults }
                .distinctBy { SearchText.fold(it) }
                .sortedBy { SearchText.fold(it) },
            tags = dao.usedTags().map { it.toTag() }
        )
    }

    /**
     * The sessions to answer [question] from (issue #49): those with
     * the most transcript lines matching any of its words (without
     * stop-words) in the FTS index, best first, at most [limit], and
     * passing [filters] as in [search]. When no line matches (the
     * question shares no word with any transcript) the newest
     * [FALLBACK_SESSIONS] sessions passing the filters are used, so
     * the answer can still look at something.
     *
     * [semantic] (issue #61) are sessions close to the question in
     * meaning, closest first. They are fused with the word matches by
     * reciprocal-rank fusion, so a session that only paraphrases the
     * question can be chosen; the filters apply to them as well.
     */
    suspend fun candidates(
        question: String,
        filters: SearchFilters = SearchFilters(),
        limit: Int = MAX_CANDIDATES,
        semantic: List<Long> = emptyList()
    ): List<Candidate> {
        val speakers = dao.speakers().groupBy { it.sessionId }
        val sessions = filtered(filters, speakers).associateBy { it.id }
        if (sessions.isEmpty()) return emptyList()
        val terms = SearchText.questionTerms(question)
        val hits = if (terms.isEmpty()) {
            emptyList()
        } else {
            dao.matchingSessions(SearchText.ftsAnyQuery(terms), filters.language, filters.since, MAX_SCANNED)
                .filter { it.sessionId in sessions }
                .take(limit)
        }
        val close = semantic.filter { it in sessions }
        val chosen = when {
            close.isNotEmpty() -> {
                val counts = hits.associate { it.sessionId to it.hits }
                SemanticRanking.fuse(
                    hits.map { it.sessionId },
                    close
                ).take(limit).map { SessionHits(it, counts[it] ?: 0) }
            }
            else -> hits.ifEmpty { sessions.keys.take(FALLBACK_SESSIONS).map { SessionHits(it, 0) } }
        }
        val summaries = dao.summaries().associate { it.sessionId to it.content }
        return chosen.map { hit ->
            Candidate(
                session = sessions.getValue(hit.sessionId),
                speakers = speakers[hit.sessionId].orEmpty(),
                summary = summaries[hit.sessionId],
                hits = hit.hits
            )
        }
    }

    /** The ids of every session passing [filters], newest first. */
    suspend fun sessionIds(filters: SearchFilters = SearchFilters()): List<Long> =
        filtered(filters, dao.speakers().groupBy { it.sessionId }).map { it.id }

    /** Every session passing [filters], newest first; [speakers] is every session's speakers. */
    private suspend fun filtered(
        filters: SearchFilters,
        speakers: Map<Long, List<SpeakerEntity>>
    ): List<SessionEntity> {
        val tagged = if (filters.tagIds.isEmpty()) {
            null
        } else {
            dao.sessionTags().filter { it.tagId in filters.tagIds }.mapTo(HashSet()) { it.sessionId }
        }
        return dao.sessions().filter { session ->
            (filters.language == null || session.language == filters.language) &&
                session.createdAt >= filters.since &&
                (filters.speaker == null || session.hasPerson(filters.speaker, speakers[session.id].orEmpty())) &&
                (tagged == null || session.id in tagged)
        }
    }

    private fun SessionEntity.hasPerson(name: String, speakers: List<SpeakerEntity>): Boolean {
        val wanted = SearchText.fold(name.trim())
        return speakers.any { SearchText.fold(it.label.trim()) == wanted } ||
            ParticipantsCodec.decode(participants).orEmpty().any { SearchText.fold(it) == wanted }
    }

    companion object {
        /** Cap on transcript lines returned per search, across sessions. */
        const val MAX_LINES: Int = 300

        /** Queries of at most this many words keep word matches first (issue #63). */
        const val SHORT_QUERY_TERMS: Int = 2

        /** Most sessions a search adds that match by meaning only (issue #63). */
        const val MAX_MEANING_SESSIONS: Int = 20

        /** Most passages found by meaning shown per session (issue #63). */
        const val MEANING_LINES_PER_SESSION: Int = 3

        /** Length of the excerpt shown for a passage or summary found by meaning. */
        const val MEANING_EXCERPT_CHARS: Int = 200

        private val SPACES = Regex("\\s+")

        /** Most sessions a cross-session question draws on (issue #49). */
        const val MAX_CANDIDATES: Int = 20

        /** Sessions used when no transcript line shares a word with the question. */
        const val FALLBACK_SESSIONS: Int = 5

        /**
         * Matching sessions read before the speaker and tag filters,
         * which are applied here rather than in SQL, drop some.
         */
        private const val MAX_SCANNED: Int = 500
    }
}
