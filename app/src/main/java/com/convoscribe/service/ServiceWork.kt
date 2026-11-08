package com.convoscribe.service

import com.convoscribe.recording.RecordingPhase

/**
 * What keeps [RecordingService] running: the one place that decides
 * whether it may give back its wake lock, leave the foreground and
 * stop (issue #121).
 *
 * Each job the service runs ends by asking this, never by stopping
 * the service on its own: the recording runs in the service's scope,
 * so stopping the service while a session is open cancels its capture
 * and the rest of the meeting is never recorded.
 *
 * @property phase the recording controller's phase; anything but
 *   [RecordingPhase.IDLE] (starting, recording, paused or stopping)
 *   holds the service.
 * @property retranscribing a re-transcription runs, including the
 *   server one after Stop.
 * @property importing an import runs.
 * @property processing post-Stop jobs (server transcript, speakers)
 *   and *Identify speakers* runs still going, including ones waiting
 *   for another run to free the speaker engine.
 */
internal data class ServiceWork(
    val phase: RecordingPhase,
    val retranscribing: Boolean,
    val importing: Boolean,
    val processing: Int
) {
    /**
     * A re-transcription or an import may start: no session is open and
     * no other one runs. Identifying speakers does not block them; they
     * wait for the speaker engine in turn.
     */
    val acceptsJob: Boolean
        get() = phase == RecordingPhase.IDLE && !retranscribing && !importing

    /** Nothing holds the service: it may give back the wake lock, leave the foreground and stop. */
    val idle: Boolean
        get() = phase == RecordingPhase.IDLE && !retranscribing && !importing && processing == 0

    /**
     * Something needs the CPU, so the service keeps its wake lock
     * (issue #124): a session is starting, recording or stopping, or
     * a re-transcription, an import or a speaker identification runs.
     * A paused session alone does not; nothing is processed then.
     */
    val needsWakeLock: Boolean
        get() = phase == RecordingPhase.LOADING ||
            phase == RecordingPhase.RECORDING ||
            phase == RecordingPhase.STOPPING ||
            retranscribing ||
            importing ||
            processing > 0
}

/**
 * Safety-net timeout of each wake lock [RecordingService] takes, in
 * case the process is killed without releasing it (issue #124).
 */
internal const val RECORDING_WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1_000L // 1 h

/** Age at which a recording renews its wake lock: half the timeout, well before it runs out. */
internal const val RECORDING_WAKE_LOCK_RENEW_AFTER_MS = RECORDING_WAKE_LOCK_TIMEOUT_MS / 2

/**
 * Whether a recording should take a fresh wake lock now (issue #124):
 * it holds none ([acquiredAtMs] is `null`) or the one it holds was
 * taken [RECORDING_WAKE_LOCK_RENEW_AFTER_MS] or more before [nowMs]. Both times
 * are `SystemClock.elapsedRealtime()` values.
 */
internal fun wakeLockRenewalDue(acquiredAtMs: Long?, nowMs: Long): Boolean =
    acquiredAtMs == null || nowMs - acquiredAtMs >= RECORDING_WAKE_LOCK_RENEW_AFTER_MS
