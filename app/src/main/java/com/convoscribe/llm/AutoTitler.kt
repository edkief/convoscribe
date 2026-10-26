package com.convoscribe.llm

import android.util.Log
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TitleSource
import com.convoscribe.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A session was just given an automatic [title]; [previous] is the one it replaced. */
data class AutoTitled(val sessionId: Long, val title: String, val previous: String)

/**
 * Names a session from its summary (issue #32). Once a summary is
 * saved, [onSummarySaved] asks the LLM for a short title, on [scope]
 * so it finishes even if the screen closes, and replaces the title
 * only while it is still the date-based default
 * ([SessionRepository.applyAutoTitle]). Each title it writes goes out
 * on [titled] so an open session screen can offer Undo.
 *
 * Off with *Name conversations automatically*, or without an LLM
 * endpoint. A failed request is logged and otherwise ignored: the
 * session simply keeps its title. [calls] is the lock that keeps one
 * LLM call at a time, shared with [MarkNotesWriter].
 */
class AutoTitler(
    private val repository: SessionRepository,
    private val chatClient: ChatClient,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val calls: Mutex = Mutex()
) {
    private val _titled = MutableSharedFlow<AutoTitled>(extraBufferCapacity = 8)

    /** Every automatic title as it is applied. */
    val titled: SharedFlow<AutoTitled> = _titled.asSharedFlow()

    /** [summary] was saved for [sessionId]: title the session if it still has its default title. */
    fun onSummarySaved(sessionId: Long, summary: String): Job? {
        val current = settings.settings.value
        if (!current.autoTitle || current.llmBaseUrl.isBlank() || current.llmModel.isBlank()) return null
        if (summary.isBlank()) return null
        return scope.launch { title(sessionId, summary) }
    }

    private suspend fun title(sessionId: Long, summary: String) {
        val session = repository.getSession(sessionId) ?: return
        if (session.titleSource != TitleSource.DEFAULT) return
        try {
            val answer = calls.withLock {
                val out = StringBuilder()
                chatClient.stream(PromptBuilder.title(summary, session.language)).collect { out.append(it) }
                out.toString()
            }
            val title = TitleCleaner.clean(answer) ?: return
            if (repository.applyAutoTitle(sessionId, title)) {
                Log.i(TAG, "auto title session=$sessionId")
                _titled.tryEmit(AutoTitled(sessionId, title, session.title))
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (failure: Exception) {
            Log.w(TAG, "auto title: LLM request failed session=$sessionId", failure)
        }
    }

    private companion object {
        const val TAG = "AutoTitler"
    }
}
