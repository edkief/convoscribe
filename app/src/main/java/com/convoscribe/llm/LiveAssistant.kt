package com.convoscribe.llm

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionSearch
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.data.TranscriptLine
import com.convoscribe.llm.embedding.SemanticMatches
import com.convoscribe.llm.embedding.SemanticSearch
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** A question heard while recording and its answer (issue #50); [questionAtMs] is when it was asked. */
data class AssistCard(
    val id: Long,
    val question: String,
    val answer: String,
    val cites: List<Citation>,
    val questionAtMs: Long
)

/** What live assist is doing, for the Record screen's single status line (issue #50). */
enum class AssistStatus {
    /** Nothing to say: on and working, or off. */
    IDLE,

    /** Just turned on: the endpoint is being probed. */
    CHECKING,

    /** Turning it on failed: the endpoint did not answer the probe. Live assist stays off. */
    UNREACHABLE,

    /** A question could not be answered and the endpoint no longer answers: the next question retries. */
    PAUSED
}

/** Why a question got no card (issue #152). */
enum class AssistMissReason {
    /** The model found no answer in this conversation or past sessions (it said NONE). */
    NO_ANSWER,

    /** The question timed out or failed. */
    FAILED
}

/** The last question that got no card, and why (issue #152). */
data class AssistMiss(val question: String, val reason: AssistMissReason)

/**
 * Live assist of the open recording (issue #50). [sessionId] is `null`
 * while no session is open; [cards] are newest first, at most
 * [LiveAssistant.MAX_CARDS]. [answering] is the question being
 * answered right now, and [missed] the last one that got no card,
 * until the next question or card (issue #152).
 */
data class LiveAssistState(
    val sessionId: Long? = null,
    val enabled: Boolean = false,
    val status: AssistStatus = AssistStatus.IDLE,
    val cards: List<AssistCard> = emptyList(),
    val answering: String? = null,
    val missed: AssistMiss? = null
)

/** The past sessions a question may be answered from, and how close they are in meaning when that is known. */
data class AssistLibrary(val sessions: List<SessionSource>, val semantic: SemanticMatches? = null)

/** Where live assist looks for past sessions (issue #50); [SearchAssistSources] in the app. */
interface AssistSources {
    /** Past sessions for [question], never [excludeId] (the recording); by meaning too when [semantic]. */
    suspend fun find(question: String, excludeId: Long, semantic: Boolean): AssistLibrary

    /** The recording ended: let go of anything held for it, such as the semantic model. */
    fun release() {}
}

/**
 * Answers questions asked aloud while recording (issue #50).
 *
 * [watch] follows the recording controller's state, like
 * [TalkingPointChecker], so the recording never waits for it and never
 * sees its failures. While a session is open with live assist on, each
 * new transcript line goes through [QuestionFilter], and again when the
 * recorder joins a lone `?` to it (issue #147); a line it lets
 * through is sent, with the last [RECENT_MS] of the transcript and
 * excerpts from earlier in this session and from past sessions
 * ([AssistSources], [CrossSessionRetriever]), to the language model,
 * which answers in at most two sentences or says
 * [PromptBuilder.NONE_ANSWER] ([AssistAnswer]). The whole answer is
 * collected before a card is shown, so e-ink redraws once per card.
 *
 * At most one question is in flight; lines that arrive meanwhile, or
 * less than [CARD_SPACING_MS] after the last card, are skipped. A
 * question gets [ANSWER_TIMEOUT_MS] in all, including the wait for
 * [calls], the lock shared with the other background calls.
 *
 * Turning it on probes the endpoint ([ChatClient.listModels], at most
 * [PROBE_TIMEOUT_MS]); when that fails it stays off with
 * [AssistStatus.UNREACHABLE]. When a question fails and the probe then
 * fails too, live assist is [AssistStatus.PAUSED]: the next question
 * probes first. Each session starts from *Live assist on by default*;
 * cards not pinned ([pin], a mark with the question and answer as its
 * note) are dropped when the session ends.
 */
class LiveAssistant(
    private val repository: SessionRepository,
    private val sources: AssistSources,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val mark: suspend (note: String, atMs: Long) -> Long?,
    private val scope: CoroutineScope,
    private val calls: Mutex = Mutex(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow(LiveAssistState())

    /** Live assist of the open session. */
    val state: StateFlow<LiveAssistState> = _state.asStateFlow()

    /** Lines of [RecordingState.finalLines] already looked at. */
    @Volatile private var seenLines = 0

    /** The last line of [RecordingState.finalLines] looked at, and whether [QuestionFilter] let it through. */
    @Volatile private var lastLine: String? = null

    @Volatile private var lastLineCandidate = false

    @Volatile private var probe: Job? = null

    @Volatile private var answering: Job? = null

    @Volatile private var lastCardAt: Long? = null

    private val cardIds = AtomicLong(1L)

    /** Follow [state], the recording controller's, for as long as the returned job runs. */
    fun watch(state: Flow<RecordingState>): Job = scope.launch {
        state.distinctUntilChangedBy { listOf(it.phase, it.sessionId, it.finalLines.size, it.finalLines.lastOrNull()) }
            .collect { onState(it) }
    }

    private fun onState(recording: RecordingState) {
        val open = recording.sessionId?.takeIf { recording.phase.active }
        val current = _state.value.sessionId
        if (open != current) {
            if (current != null) end()
            if (open != null) begin(open, recording.finalLines)
            return
        }
        if (open == null) return
        val lines = recording.finalLines
        val fresh = lines.drop(seenLines)
        seenLines = lines.size
        val last = lines.lastOrNull()
        if (fresh.isEmpty() && last != null && last != lastLine) {
            // The recognizer's lone "?" was joined to the last line (issue
            // #147): it may only now read as a question. One that already
            // did was considered when it arrived.
            val candidate = QuestionFilter.isCandidate(last)
            if (candidate && !lastLineCandidate) consider(open, last)
            lastLine = last
            lastLineCandidate = lastLineCandidate || candidate
            return
        }
        lastLine = last
        lastLineCandidate = last != null && QuestionFilter.isCandidate(last)
        // Several lines at once: the newest question is the one still being talked about.
        fresh.lastOrNull { QuestionFilter.isCandidate(it) }?.let { consider(open, it) }
    }

    private fun begin(sessionId: Long, lines: List<String>) {
        seenLines = lines.size
        lastLine = lines.lastOrNull()
        lastLineCandidate = true
        lastCardAt = null
        _state.value = LiveAssistState(sessionId = sessionId)
        val current = settings.settings.value
        if (current.liveAssistDefault && current.llmConfigured) turnOn(sessionId)
    }

    private fun end() {
        probe?.cancel()
        answering?.cancel()
        probe = null
        answering = null
        sources.release()
        _state.value = LiveAssistState()
    }

    /**
     * Turn live assist on or off for the open session, from the Record
     * screen's switch. On probes the endpoint first; nothing happens
     * without an open session or a language model.
     */
    fun setEnabled(enabled: Boolean) {
        val id = _state.value.sessionId ?: return
        if (!enabled) {
            probe?.cancel()
            answering?.cancel()
            _state.update { it.copy(enabled = false, status = AssistStatus.IDLE, answering = null, missed = null) }
            return
        }
        if (_state.value.enabled || !settings.settings.value.llmConfigured) return
        turnOn(id)
    }

    private fun turnOn(sessionId: Long) {
        _state.update { it.copy(enabled = true, status = AssistStatus.CHECKING) }
        probe?.cancel()
        probe = scope.launch {
            val ok = reachable()
            _state.update {
                when {
                    it.sessionId != sessionId || !it.enabled -> it
                    ok -> it.copy(status = AssistStatus.IDLE)
                    else -> it.copy(enabled = false, status = AssistStatus.UNREACHABLE)
                }
            }
            if (!ok) Log.i(TAG, "live assist: endpoint not reachable session=$sessionId")
        }
    }

    /** Keep a card: a mark at its question with the question and answer as its note. */
    suspend fun pin(cardId: Long) {
        val card = _state.value.cards.firstOrNull { it.id == cardId } ?: return
        mark(note(card), card.questionAtMs) ?: return
        dismiss(cardId)
    }

    /** Drop a card. */
    fun dismiss(cardId: Long) {
        _state.update { state -> state.copy(cards = state.cards.filterNot { it.id == cardId }) }
    }

    private fun consider(sessionId: Long, line: String) {
        val current = _state.value
        if (!current.enabled || current.status == AssistStatus.CHECKING) return
        if (answering?.isActive == true) return
        val last = lastCardAt
        if (last != null && clock() - last < CARD_SPACING_MS) return
        answering = scope.launch { answer(sessionId, line) }
    }

    private suspend fun answer(sessionId: Long, line: String) {
        val question = line.trim()
        val startedAt = clock()
        updateOpen(sessionId) { it.copy(answering = question, missed = null) }
        if (_state.value.status == AssistStatus.PAUSED) {
            if (!reachable()) {
                Log.i(TAG, "live assist: still paused session=$sessionId")
                updateOpen(sessionId) { it.copy(answering = null, missed = AssistMiss(question, AssistMissReason.FAILED)) }
                return
            }
            updateOpen(sessionId) { it.copy(status = AssistStatus.IDLE) }
        }
        Log.i(TAG, "live assist: asking session=$sessionId")
        val card = try {
            // Boxed: a null card (nothing to show) is not a timeout.
            val answered = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { listOf(ask(sessionId, line)) }
                ?: throw LlmException("No answer within ${ANSWER_TIMEOUT_MS / 1_000} s")
            answered.single()
        } catch (ce: CancellationException) {
            updateOpen(sessionId) { it.copy(answering = null) }
            throw ce
        } catch (failure: Exception) {
            Log.w(TAG, "live assist: question failed session=$sessionId", failure)
            val paused = !reachable()
            updateOpen(sessionId) {
                it.copy(
                    answering = null,
                    missed = AssistMiss(question, AssistMissReason.FAILED),
                    status = if (paused) AssistStatus.PAUSED else it.status
                )
            }
            return
        }
        if (card == null) {
            Log.i(TAG, "live assist: no answer session=$sessionId in ${clock() - startedAt} ms")
            updateOpen(sessionId) { it.copy(answering = null, missed = AssistMiss(question, AssistMissReason.NO_ANSWER)) }
            return
        }
        Log.i(TAG, "live assist: card session=$sessionId in ${clock() - startedAt} ms")
        val shown = updateOpen(sessionId) {
            it.copy(cards = (listOf(card) + it.cards).take(MAX_CARDS), answering = null, missed = null)
        }
        if (shown) lastCardAt = clock()
    }

    /** Apply [change] if [sessionId] is still open with live assist on; whether it was. */
    private fun updateOpen(sessionId: Long, change: (LiveAssistState) -> LiveAssistState): Boolean {
        var applied = false
        _state.update {
            applied = it.sessionId == sessionId && it.enabled
            if (applied) change(it) else it
        }
        return applied
    }

    /** The card for [line], or `null` when the model has nothing to show. */
    private suspend fun ask(sessionId: Long, line: String): AssistCard? {
        val session = repository.getSession(sessionId) ?: return null
        val lines = TranscriptFormatter.lines(repository.getSegments(sessionId), repository.getSpeakers(sessionId))
        val (earlier, recent) = split(lines)
        val askedAt = lines.lastOrNull { it.text.trim() == line.trim() }?.startMs ?: lines.lastOrNull()?.startMs ?: 0L
        val current = settings.settings.value
        val library = sources.find(line, sessionId, current.liveAssistSemantic)
        val own = SessionSource(
            id = sessionId,
            title = session.title,
            createdAt = session.createdAt,
            language = session.language,
            lines = earlier
        ).takeIf { earlier.isNotEmpty() }
        val excerpts = CrossSessionRetriever.select(
            sources = listOfNotNull(own) + library.sessions.filter { it.id != sessionId },
            question = line,
            maxChars = minOf(current.maxTranscriptChars, EXCERPT_CHARS),
            semantic = library.semantic
        )
        val messages = PromptBuilder.liveAssist(line, recentText(recent), excerpts)
        val reply = calls.withLock {
            val out = StringBuilder()
            chatClient.stream(messages).collect { out.append(it) }
            out.toString()
        }
        val parsed = AssistAnswer.parse(reply, excerpts) ?: return null
        return AssistCard(cardIds.getAndIncrement(), line.trim(), parsed.text, parsed.cites, askedAt)
    }

    /** Whether the endpoint answers within [PROBE_TIMEOUT_MS]; the call may block, so it is only waited for. */
    private suspend fun reachable(): Boolean {
        val call = scope.async { runCatching { chatClient.listModels() }.isSuccess }
        return withTimeoutOrNull(PROBE_TIMEOUT_MS) { call.await() } ?: false.also { call.cancel() }
    }

    companion object {
        /** How much of the latest transcript goes with a question. */
        const val RECENT_MS: Long = 120_000L

        /** At most this much of it, keeping the newest. */
        const val RECENT_CHARS: Int = 4_000

        /** Excerpts of this session and past ones, at most; less with a smaller *Max transcript characters*. */
        const val EXCERPT_CHARS: Int = 6_000

        /** The quietest the card area may be: at most one new card this often. */
        const val CARD_SPACING_MS: Long = 20_000L

        /** All a question may take, from the excerpts to the end of the answer. */
        const val ANSWER_TIMEOUT_MS: Long = 15_000L

        /** How long the endpoint has to answer the probe. */
        const val PROBE_TIMEOUT_MS: Long = 3_000L

        /** Cards kept on the Record screen; an older one makes way for a new one. */
        const val MAX_CARDS: Int = 3

        /** The note of a pinned card's mark. */
        fun note(card: AssistCard): String = "Q: ${card.question}\nA: ${card.answer}"

        /** [lines] before the last [RECENT_MS], and the last [RECENT_MS] (measured from the newest line). */
        fun split(lines: List<TranscriptLine>): Pair<List<TranscriptLine>, List<TranscriptLine>> {
            val newest = lines.maxOfOrNull { maxOf(it.startMs, it.endMs) } ?: return emptyList<TranscriptLine>() to
                emptyList()
            val from = newest - RECENT_MS
            return lines.partition { maxOf(it.startMs, it.endMs) < from }
        }

        /** [recent] as the model reads it, cut to [RECENT_CHARS] from the end. */
        fun recentText(recent: List<TranscriptLine>): String =
            recent.joinToString("\n") { TranscriptFormatter.lineText(it) }.takeLast(RECENT_CHARS)
    }
}

/**
 * Past sessions for live assist (issue #50), as the Ask tab finds them
 * (issue #49): [SessionSearch.candidates] by words and, when asked and
 * the model is installed, by meaning through a [SemanticSearch] kept
 * for the recording and closed at its end.
 */
class SearchAssistSources(
    private val search: SessionSearch,
    private val repository: SessionRepository,
    private val semanticSearch: () -> SemanticSearch
) : AssistSources {

    private var semantic: SemanticSearch? = null

    override suspend fun find(question: String, excludeId: Long, semantic: Boolean): AssistLibrary {
        val matches = if (semantic) model()?.match(question) else null
        val sessions = search.candidates(question, semantic = matches?.sessions.orEmpty())
            .filter { it.session.id != excludeId }
            .map { candidate ->
                val session = candidate.session
                SessionSource(
                    id = session.id,
                    title = session.title,
                    createdAt = session.createdAt,
                    language = session.language,
                    lines = TranscriptFormatter.lines(repository.getSegments(session.id), candidate.speakers),
                    summary = candidate.summary
                )
            }
        return AssistLibrary(sessions, matches)
    }

    private fun model(): SemanticSearch? {
        val model = semantic ?: semanticSearch().also { semantic = it }
        return model.takeIf { it.isAvailable() }
    }

    override fun release() {
        semantic?.close()
        semantic = null
    }
}
