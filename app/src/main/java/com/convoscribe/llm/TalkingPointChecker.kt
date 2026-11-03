package com.convoscribe.llm

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RecordingState
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
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

/**
 * Ticks a recording's talking points as they come up (issue #103).
 *
 * [watch] follows the recording. While a session is recording, every
 * *Check talking points every* seconds
 * ([com.convoscribe.settings.AppSettings.talkingPointCheckSeconds]),
 * the speech since the last check ([TalkingPointWindow]) and the points
 * still open go to the language model ([PromptBuilder.talkingPoints]),
 * and the points it names ([TalkingPointAnswer]) are ticked
 * ([SessionRepository.tickTalkingPointsAutomatically]). Pause stops
 * the checks and Resume starts them again; after Stop, one last check
 * reads the speech since the last one.
 *
 * Checks never get in the way of the recording: they run on [scope],
 * one at a time and under [calls], the lock shared with
 * [MarkNotesWriter] and [AutoTitler]. A failure (no answer within
 * [timeoutMs], an error, an unreadable answer) is logged once per run of
 * failures. The next check then waits twice as long (up to
 * [MAX_BACKOFF_MS]) and covers the same speech again. Only after
 * [FAILURES_BEFORE_NOTE] failures in a row is the session put in
 * [failing], for the Record screen's quiet note; a success clears it.
 *
 * Points ticked or unticked by hand are never sent nor changed. Nothing
 * is checked without a language model, without open points, or with
 * the interval at 0.
 */
class TalkingPointChecker(
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val calls: Mutex = Mutex(),
    private val timeoutMs: Long = REQUEST_TIMEOUT_MS
) {
    private val _failing = MutableStateFlow<Set<Long>>(emptySet())

    /** Sessions whose last [FAILURES_BEFORE_NOTE] or more checks failed. */
    val failing: StateFlow<Set<Long>> = _failing.asStateFlow()

    /** What is known of the session being watched. */
    private class Watched(val sessionId: Long, var checkedUpToMs: Long) {
        var failures: Int = 0
        var loop: Job? = null
    }

    private var watched: Watched? = null

    /** Follow [state], the recording controller's, for as long as the returned job runs. */
    fun watch(state: Flow<RecordingState>): Job = scope.launch {
        state.distinctUntilChangedBy { it.phase to it.sessionId }.collect { onState(it) }
    }

    private suspend fun onState(state: RecordingState) {
        val current = watched
        val id = state.sessionId
        if (current != null && (!state.phase.active || id != current.sessionId)) {
            if (state.phase == RecordingPhase.STOPPING && id == current.sessionId) {
                // Wait for IDLE: the last words are only saved once the stop is done.
                current.loop?.cancelAndJoin()
                return
            }
            watched = null
            current.loop?.cancelAndJoin()
            scope.launch {
                runCheck(current, final = true)
                _failing.update { it - current.sessionId }
            }
        }
        if (id == null || !state.phase.active) return
        val watching = watched ?: Watched(id, checkedUpToMs = state.elapsedMs).also {
            watched = it
            _failing.update { set -> set - id }
        }
        if (state.phase == RecordingPhase.PAUSED) {
            watching.loop?.cancelAndJoin()
            watching.loop = null
        } else if (watching.loop?.isActive != true) {
            watching.loop = scope.launch { loop(watching) }
        }
    }

    private suspend fun loop(watching: Watched) {
        // Ends when cancelled: delay() and the check give way to it.
        while (true) {
            val seconds = settings.settings.value.talkingPointCheckSeconds
            if (seconds <= 0) {
                // Off: look again later, in case it is turned on.
                delay(OFF_POLL_MS)
                continue
            }
            delay(waitMs(seconds * 1_000L, watching.failures))
            runCheck(watching, final = false)
        }
    }

    /** One check of [watching]'s speech since its last one; any failure is counted, never thrown. */
    private suspend fun runCheck(watching: Watched, final: Boolean) {
        val id = watching.sessionId
        try {
            val endMs = check(watching) ?: return
            watching.checkedUpToMs = endMs
            if (watching.failures > 0) Log.i(TAG, "talking points: checks work again session=$id")
            watching.failures = 0
            _failing.update { it - id }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Exception) {
            watching.failures++
            if (watching.failures == 1) Log.w(TAG, "talking points: check failed session=$id", failure)
            if (!final && watching.failures >= FAILURES_BEFORE_NOTE) _failing.update { it + id }
        }
    }

    /**
     * Ask about [watching]'s open points and tick those covered. Returns
     * where the next check starts, or `null` when there was nothing to
     * check (no language model, no open points, no new speech, or off).
     */
    private suspend fun check(watching: Watched): Long? {
        val current = settings.settings.value
        if (!current.llmConfigured || current.talkingPointCheckSeconds <= 0) return null
        val id = watching.sessionId
        val open = repository.getTalkingPoints(id).filter { !it.covered && !it.byUser }
        if (open.isEmpty()) return null
        val texts = open.map { it.text }
        val lines = TranscriptFormatter.lines(repository.getSegments(id), repository.getSpeakers(id))
        val budget = PromptBuilder.talkingPointsTranscriptBudget(texts, current.maxTranscriptChars)
        val window = TalkingPointWindow.select(lines, watching.checkedUpToMs, budget) ?: return null
        val messages = PromptBuilder.talkingPoints(texts, window.text)
        val answer = calls.withLock {
            withTimeoutOrNull(timeoutMs) {
                val out = StringBuilder()
                chatClient.stream(messages).collect { out.append(it) }
                out.toString()
            }
        } ?: throw LlmException("No answer within ${timeoutMs / 1_000} s")
        val covered = TalkingPointAnswer.parse(answer, open.size)
            ?: throw LlmException("Unreadable answer: ${answer.take(ANSWER_IN_ERROR_CHARS)}")
        val ticks = covered.associate { point ->
            val atMs = point.atMs?.takeIf { it in window.firstMs..window.endMs } ?: window.lastMs
            open[point.number - 1].id to atMs
        }
        val ticked = repository.tickTalkingPointsAutomatically(id, ticks)
        if (ticked > 0) Log.i(TAG, "talking points: ticked=$ticked session=$id")
        return window.endMs
    }

    companion object {
        /** A check waits at most this long for its answer once it has the lock. */
        const val REQUEST_TIMEOUT_MS: Long = 60_000L

        /** The longest wait between checks while they keep failing. */
        const val MAX_BACKOFF_MS: Long = 300_000L

        /** Failed checks in a row before the Record screen says so. */
        const val FAILURES_BEFORE_NOTE: Int = 2

        /** How often an interval of 0 (off) is looked at again. */
        const val OFF_POLL_MS: Long = 5_000L

        private const val ANSWER_IN_ERROR_CHARS = 100

        /** The wait before the next check: [intervalMs], doubled for each of the [failures] in a row. */
        fun waitMs(intervalMs: Long, failures: Int): Long {
            if (failures <= 0) return intervalMs
            val cap = maxOf(intervalMs, MAX_BACKOFF_MS)
            var wait = intervalMs
            repeat(failures) { wait = (wait * 2).coerceAtMost(cap) }
            return wait
        }
    }
}
