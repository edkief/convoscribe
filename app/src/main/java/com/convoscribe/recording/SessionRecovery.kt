package com.convoscribe.recording

import com.convoscribe.audio.WavWriter
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.cleanAudioFile
import java.io.File

/**
 * Crash-recovery pass the app runs on every cold start.
 *
 * A normal session lifecycle is `RECORDING → TRANSCRIBED →
 * DIARIZING → DIARIZED` (or `DIARIZATION_FAILED`); if the process
 * is killed before the recording service finishes the WAV or the
 * diarization use case lands, the row stays at the in-progress
 * status forever. On next launch this class walks those rows and
 * patches them so the user can still see the transcript in the
 * session list (and re-run diarization if the file is fine).
 *
 * * **RECORDING**: the process died mid-capture. The WAV header
 *   was laid out but never closed; [WavWriter.repairHeader] rewrites
 *   the RIFF/data sizes from the file length, and the duration is
 *   computed as `(file.length() - HEADER_SIZE) / 32` ms (32 bytes
 *   per millisecond: 2 bytes × 16 000 samples / 1 000 ms). The
 *   status is moved to `TRANSCRIBED` so the home screen shows the
 *   row and the post-record pipeline can pick it up if the user
 *   hits retry. A live-cleaned session's cleaned WAV (issue #37) is
 *   repaired the same way; continuing the session pads it if it fell
 *   behind.
 * * **DIARIZING**: the process died mid-diarization. The WAV is
 *   already valid; the session row is set to `DIARIZATION_PENDING`
 *   so the charging job (or the user) picks it up again (issue #11).
 *
 * Recovery never throws: missing or unreadable WAV files are
 * logged on the caller side (or simply skipped, see the caller)
 * and the loop continues to the next session.
 *
 * @property repository the [SessionRepository] we read the stale
 *   status from and write the patched row back to.
 */
class SessionRecovery(private val repository: SessionRepository) {

    /**
     * Walk every session in `RECORDING` or `DIARIZING` and patch
     * it to `TRANSCRIBED` or `DIARIZATION_PENDING`. Returns when the loop has nothing left
     * to do; safe to call repeatedly because the next pass will
     * find an empty list.
     *
     * The loop is intentionally synchronous at the database layer
     * (`for (... in repository.sessionsWithStatus(...))`): we are
     * the only writer at this point (the foreground service has
     * not started yet), so interleaving would only race against
     * itself.
     */
    suspend fun recover() {
        val recordingSessions = repository.sessionsWithStatus(SessionStatus.RECORDING)
        for (session in recordingSessions) {
            val audio = File(session.audioPath)
            if (audio.exists()) {
                WavWriter.repairHeader(audio)
                val bytes = audio.length() - WavWriter.HEADER_SIZE
                // 32 bytes per millisecond: 16-bit (2 bytes) ×
                // 16 000 samples/s ÷ 1 000 ms/s = 32. Negative
                // values mean the WAV is shorter than the header
                // (the file was wiped, or only the header landed);
                // clamp to 0 so a corrupt file does not produce a
                // negative duration.
                val durationMs = (bytes / BYTES_PER_MS).coerceAtLeast(0L)
                repository.setDuration(session.id, durationMs)
            }
            if (session.cleanedWith != null) {
                session.cleanAudioFile().takeIf { it.exists() }?.let(WavWriter::repairHeader)
            }
            repository.setStatus(session.id, SessionStatus.TRANSCRIBED)
        }

        val diarizingSessions = repository.sessionsWithStatus(SessionStatus.DIARIZING)
        for (session in diarizingSessions) {
            // No file work: the WAV was already closed when the
            // recording finished, the diarizer held the session in
            // its native memory only for the duration of the
            // process that died.
            repository.setStatus(session.id, SessionStatus.DIARIZATION_PENDING)
        }
    }

    private companion object {
        /**
         * Bytes on disk per millisecond of mono 16 kHz PCM16
         * audio: 16 000 samples/s × 2 bytes/sample ÷ 1 000 ms/s =
         * 32. Computed once so the recovery loop can avoid
         * re-doing the same arithmetic.
         */
        const val BYTES_PER_MS: Long = 32L
    }
}
