package com.convoscribe.data

/**
 * Narrows a search: [language] `null` is any, only sessions created
 * at or after [since] count, and [speaker] (a speaker name or
 * expected participant, compared ignoring case and accents) `null`
 * is anyone.
 */
data class SearchFilters(
    val language: String? = null,
    val since: Long = 0L,
    val speaker: String? = null
)

/** A matching transcript line: where it starts, who said it, and the snippet. */
data class SearchLine(
    val segmentId: Long,
    val startMs: Long,
    val speaker: String?,
    val speakerIndex: Int?,
    val text: Highlighted
)

/**
 * One session's matches: its [title] (highlighted when it matched),
 * the speaker names that matched, an excerpt of the saved summary
 * when it matched, and the matching transcript [lines] in reading
 * order.
 */
data class SearchResult(
    val session: SessionEntity,
    val title: Highlighted,
    val speakers: List<Highlighted>,
    val summary: Highlighted?,
    val lines: List<SearchLine>
)

/** What the filters can offer: languages and names found across sessions. */
data class SearchOptions(
    val languages: List<String> = emptyList(),
    val speakers: List<String> = emptyList()
)

/**
 * Cross-session search (issue #24). Transcript lines come from the
 * FTS4 index ([SearchDao.findSegments]); titles, speaker names and
 * the latest saved summary are matched here with [SearchText], so
 * every source ignores case and accents and matches word prefixes
 * the same way. Results are grouped per session, newest first.
 */
class SessionSearch(private val dao: SearchDao) {

    suspend fun search(query: String, filters: SearchFilters = SearchFilters()): List<SearchResult> {
        val terms = SearchText.terms(query)
        if (terms.isEmpty()) return emptyList()
        val speakers = dao.speakers().groupBy { it.sessionId }
        val sessions = dao.sessions().filter { session ->
            (filters.language == null || session.language == filters.language) &&
                session.createdAt >= filters.since &&
                (filters.speaker == null || session.hasPerson(filters.speaker, speakers[session.id].orEmpty()))
        }
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

        return sessions.mapNotNull { session ->
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
    }

    /**
     * Languages used by sessions (in first-seen order, newest session
     * first) and the names of their speakers and expected
     * participants, sorted, without the default "Speaker N" labels.
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
                .sortedBy { SearchText.fold(it) }
        )
    }

    private fun SessionEntity.hasPerson(name: String, speakers: List<SpeakerEntity>): Boolean {
        val wanted = SearchText.fold(name.trim())
        return speakers.any { SearchText.fold(it.label.trim()) == wanted } ||
            ParticipantsCodec.decode(participants).orEmpty().any { SearchText.fold(it) == wanted }
    }

    companion object {
        /** Cap on transcript lines returned per search, across sessions. */
        const val MAX_LINES: Int = 300
    }
}
