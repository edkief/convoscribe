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
}
