package com.convoscribe.diarization

import android.util.Log
import com.convoscribe.TAG
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.settings.SpeakerIdMode

/**
 * The charging job's work (issue #11): identify the speakers of every
 * [SessionStatus.DIARIZATION_PENDING] session, one at a time, oldest
 * first, until none is left.
 *
 * Kept free of Android types so it can be unit-tested;
 * [com.convoscribe.service.DiarizationJobService] runs it and cancels
 * it when the device is unplugged or the system stops the job, which
 * leaves the current session pending ([DiarizeSessionUseCase.run]).
 *
 * @property awaitRecovery suspends until the app-start crash recovery
 *   has run, so it cannot reset a session this run has just started.
 * @property busy whether a recording, import or re-transcription is
 *   running; the job then gives way and asks to be retried later.
 * @property held whether the user cancelled a run during this charge
 *   (issue #64); nothing runs automatically until the next one.
 */
class PendingDiarizationRunner(
    private val repository: SessionRepository,
    private val diarize: DiarizeSessionUseCase,
    private val settings: SettingsRepository,
    private val awaitRecovery: suspend () -> Unit = {},
    private val busy: () -> Boolean = { false },
    private val held: () -> Boolean = { false }
) {
    /** Why [run] returned. */
    enum class Outcome {
        /** No pending session is left. */
        DONE,

        /** Something else holds the device; retry later. */
        BUSY,

        /** The setting is *On demand*; nothing runs automatically. */
        DISABLED,

        /** The user cancelled a run during this charge; wait for the next one. */
        HELD
    }

    suspend fun run(): Outcome {
        awaitRecovery()
        // A session is tried once per run: one that is pending again
        // straight after (deleted mid-run, say) must not spin the loop.
        val tried = mutableSetOf<Long>()
        while (true) {
            if (SpeakerIdMode.of(settings.settings.value.speakerIdMode) == SpeakerIdMode.MANUAL) return Outcome.DISABLED
            if (held()) return Outcome.HELD
            if (busy()) return Outcome.BUSY
            val next = repository.sessionsWithStatus(SessionStatus.DIARIZATION_PENDING)
                .firstOrNull { it.id !in tried }
                ?: return Outcome.DONE
            tried += next.id
            Log.i(TAG, "charging job: identifying speakers session=${next.id}")
            diarize.runIfPending(next.id)
        }
    }

    /** Whether any session waits for its speakers. */
    suspend fun hasPending(): Boolean = repository.sessionsWithStatus(SessionStatus.DIARIZATION_PENDING).isNotEmpty()
}
