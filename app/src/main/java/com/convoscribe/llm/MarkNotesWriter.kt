package com.convoscribe.llm

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TranscriptFormatter
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How writing a session's mark notes is going (issue #48): [done] of
 * [total] notes written. [error] is set when a run the user started
 * failed; the run is over then.
 */
data class MarkNotesProgress(
    val done: Int,
    val total: Int,
    val error: String? = null
) {
    val running: Boolean get() = error == null
}

/**
 * Writes the full note of each mark (issue #48): for every mark, the
 * LLM gets the mark's own note and the transcript around it
 * ([MarkWindow], [PromptBuilder.markNote]) and its answer is saved on
 * the mark.
 *
 * Runs in the app's [scope] rather than a screen's, so it goes on when
 * the session is closed and the charging job can start it. One run per
 * session, and one LLM call at a time overall: the endpoint is often a
 * single small server; [calls] is that lock, shared with [AutoTitler]. Each note is saved as soon as it is complete; a
 * call that fails or is cancelled saves nothing for its mark.
 *
 * [onDiarized] is the automatic path, called once a session's speakers
 * are identified so the notes use their names. It only writes notes
 * that are missing and fails quietly (the endpoint may simply be out of
 * reach). [start] is the Summary tab's *Write notes* / *Regenerate*:
 * it rewrites every note and keeps a failure in [progress] to show.
 */
class MarkNotesWriter(
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val calls: Mutex = Mutex()
) {
    private val _progress = MutableStateFlow<Map<Long, MarkNotesProgress>>(emptyMap())

    /** The run of each session that has one going, or a failed manual run. */
    val progress: StateFlow<Map<Long, MarkNotesProgress>> = _progress.asStateFlow()

    private val jobs = mutableMapOf<Long, Job>()

    /** Whether an LLM endpoint is set up. */
    fun configured(): Boolean = settings.settings.value.let { it.llmBaseUrl.isNotBlank() && it.llmModel.isNotBlank() }

    /**
     * Write the notes of [sessionId]'s marks, all of them or with
     * [onlyMissing] those that have none. Returns the run, or `null`
     * when no endpoint is set up or the session already has one going.
     */
    fun start(sessionId: Long, onlyMissing: Boolean = false): Job? = launch(sessionId, onlyMissing, quiet = false)

    /**
     * A session's speakers were identified: write its missing notes if
     * *Write notes for marks automatically* is on.
     */
    fun onDiarized(sessionId: Long): Job? {
        if (!settings.settings.value.autoMarkNotes) return null
        return launch(sessionId, onlyMissing = true, quiet = true)
    }

    /** Stop [sessionId]'s run; notes already written stay. Also clears a failure. */
    fun cancel(sessionId: Long) {
        synchronized(jobs) { jobs.remove(sessionId) }?.cancel()
        _progress.update { it - sessionId }
    }

    private fun launch(sessionId: Long, onlyMissing: Boolean, quiet: Boolean): Job? {
        if (!configured()) return null
        synchronized(jobs) {
            if (jobs[sessionId]?.isActive == true) return null
            val job = scope.launch(start = CoroutineStart.LAZY) { write(sessionId, onlyMissing, quiet) }
            jobs[sessionId] = job
            job.invokeOnCompletion {
                synchronized(jobs) { if (jobs[sessionId] === job) jobs.remove(sessionId) }
            }
            job.start()
            return job
        }
    }

    private suspend fun write(sessionId: Long, onlyMissing: Boolean, quiet: Boolean) {
        val marks = repository.getMarks(sessionId).filter { !onlyMissing || it.expandedNote == null }
        if (marks.isEmpty()) return
        val lines = TranscriptFormatter.lines(repository.getSegments(sessionId), repository.getSpeakers(sessionId))
        if (lines.isEmpty()) return
        val maxChars = settings.settings.value.maxTranscriptChars
        var done = 0
        _progress.update { it + (sessionId to MarkNotesProgress(done, marks.size)) }
        try {
            for (row in marks) {
                val mark = TranscriptFormatter.marks(listOf(row)).single()
                val messages = PromptBuilder.markNote(mark, MarkWindow.lines(lines, mark.atMs), maxChars)
                val text = calls.withLock {
                    val out = StringBuilder()
                    chatClient.stream(messages).collect { out.append(it) }
                    out.toString().trim()
                }
                // Skip a mark whose note was edited or that was deleted meanwhile.
                val current = repository.getMarks(sessionId).firstOrNull { it.id == row.id }
                if (text.isNotEmpty() && current != null && current.note == row.note) {
                    repository.setMarkExpansion(row.id, text)
                }
                done++
                _progress.update { it + (sessionId to MarkNotesProgress(done, marks.size)) }
            }
            Log.i(TAG, "mark notes written session=$sessionId notes=$done")
            _progress.update { it - sessionId }
        } catch (ce: CancellationException) {
            _progress.update { it - sessionId }
            throw ce
        } catch (failure: Exception) {
            Log.w(TAG, "mark notes: LLM request failed session=$sessionId", failure)
            _progress.update {
                if (quiet) {
                    it - sessionId
                } else {
                    it + (sessionId to MarkNotesProgress(done, marks.size, error = failure.toUserMessage()))
                }
            }
        }
    }
}
