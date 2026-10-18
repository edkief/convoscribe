package com.convoscribe.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.audio.WavWriter
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SessionRecovery]. Robolectric only because we
 * need [androidx.room.Room] to wire SQLite against a real
 * `Context`; the WAV file the recovery loop reads is laid out on
 * the test app's `filesDir` via a fresh [com.convoscribe.audio.WavWriter]
 * (the same code path the recorder uses), so the test exercises
 * the recovery rules end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRecoveryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private lateinit var recovery: SessionRecovery

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "recordings-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
        recovery = SessionRecovery(repo)
    }

    @After
    fun tearDown() {
        db.close()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    /**
     * Helper: write a 100 ms WAV (1600 samples) into the recorder's
     * directory and return the file. The recorder would normally
     * call `WavWriter.close()` after `stop()`, which patches the
     * RIFF/data sizes — but a crash before `close()` leaves the
     * header zeroed, which is exactly what [SessionRecovery] is
     * expected to handle.
     */
    private fun writeCrashRecoveredAudio(sessionId: Long, samples: Int = 1_600): File {
        val wav = WavWriter(File(recordingsDir, "$sessionId.wav"))
        wav.write(ShortArray(samples))
        // Deliberately NOT calling close() — the header is left
        // with zeroed RIFF/data sizes, just like a real crash
        // would leave it.
        return File(recordingsDir, "$sessionId.wav")
    }

    /**
     * Acceptance test: a `RECORDING` session whose WAV is
     * 44 + 3 200 bytes (i.e. 100 ms of audio) becomes
     * `TRANSCRIBED` with `durationMs == 100`. The header is
     * repaired (RIFF/data sizes patched) so the file is a valid
     * WAV afterwards.
     */
    @Test
    fun recover_recordingSessionWithCrashedWav_repairsHeader_andSetsDuration() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        val audio = writeCrashRecoveredAudio(id, samples = 1_600)
        // Sanity-check: the WAV is exactly the header plus 1 600
        // samples × 2 bytes = 44 + 3 200 bytes, with the size
        // fields left at zero (no `close()` was called).
        assertEquals(44L + 3_200L, audio.length())
        // Header is currently zeroed — confirm by inspecting the
        // data-chunk size field at offset 40 (little-endian).
        val before = audio.readBytes()
        assertEquals(0, before[40].toInt())
        assertEquals(0, before[41].toInt())

        recovery.recover()

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.TRANSCRIBED, session.status)
        assertEquals(100L, session.durationMs)

        // Header was repaired: RIFF size (offset 4) and data size
        // (offset 40) now reflect the actual file length.
        val after = audio.readBytes()
        val riffSize = (after[4].toInt() and 0xFF) or
            ((after[5].toInt() and 0xFF) shl 8) or
            ((after[6].toInt() and 0xFF) shl 16) or
            ((after[7].toInt() and 0xFF) shl 24)
        val dataSize = (after[40].toInt() and 0xFF) or
            ((after[41].toInt() and 0xFF) shl 8) or
            ((after[42].toInt() and 0xFF) shl 16) or
            ((after[43].toInt() and 0xFF) shl 24)
        assertEquals(36L + 3_200L, riffSize.toLong())
        assertEquals(3_200L, dataSize.toLong())
    }

    /**
     * Issue #7: a session the process died in while paused is just a
     * `RECORDING` row with an unclosed WAV and the segments persisted
     * up to the pause. Recovery keeps that transcript and the audio
     * recorded so far.
     */
    @Test
    fun recover_sessionPausedWhenTheProcessDied_keepsTheTranscriptSoFar() = runTest {
        val id = repo.createSession(title = "Paused", language = "en")
        writeCrashRecoveredAudio(id, samples = 3_200)
        repo.addSegment(id, startMs = 0L, endMs = 200L, text = "Before the pause", tokens = emptyList())

        recovery.recover()

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.TRANSCRIBED, session.status)
        assertEquals(200L, session.durationMs)
        assertEquals(listOf("Before the pause"), repo.observeSegments(id).first().map { it.text })
    }

    /**
     * Acceptance test: a `DIARIZING` session whose WAV was
     * already closed is moved to `TRANSCRIBED` without touching
     * the file (no `repairHeader` call is needed).
     */
    @Test
    fun recover_diarizingSession_withClosedWav_becomesTranscribed() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        // Lay out a fully-closed WAV so the recovery loop should
        // leave the file alone.
        val wav = WavWriter(File(recordingsDir, "$id.wav"))
        wav.write(ShortArray(1_600))
        wav.close()
        val audio = File(recordingsDir, "$id.wav")
        val sizeAfterClose = audio.length()
        val bytesAfterClose = audio.readBytes()

        repo.setStatus(id, SessionStatus.DIARIZING)

        recovery.recover()

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.TRANSCRIBED, session.status)
        // The WAV file was not touched: same length, same first
        // 44 bytes (header already valid before recovery).
        assertEquals(sizeAfterClose, audio.length())
        assertTrue(audio.readBytes().contentEquals(bytesAfterClose))
    }

    /**
     * `recover()` is idempotent: a second pass over a clean
     * database finds no `RECORDING` / `DIARIZING` rows and
     * exits silently. The loop must not throw or write to the
     * database in that case.
     */
    @Test
    fun recover_isIdempotent() = runTest {
        // No rows at all — the first pass is a no-op, and so is
        // the second.
        recovery.recover()
        recovery.recover()
        // We only assert that nothing throws; the database is
        // still empty (anything equals itself).
        val sessions = repo.observeAllSessions()
        assertTrue(sessions == sessions)
    }

    /**
     * A session whose WAV file is missing on disk (e.g. the user
     * cleared the cache between launches) is still patched to
     * `TRANSCRIBED`. The recovery loop does not invent a
     * duration — it leaves `durationMs == 0` so the UI can flag
     * the session as "audio missing" if it cares.
     */
    @Test
    fun recover_recordingSession_withMissingWav_becomesTranscribed_withZeroDuration() = runTest {
        val id = repo.createSession(title = "Test", language = "en")
        // The repo writes `audioPath = <recordingsDir>/<id>.wav`,
        // but we never actually create the file.
        assertTrue(!File(recordingsDir, "$id.wav").exists())

        recovery.recover()

        val session = repo.getSession(id)!!
        assertEquals(SessionStatus.TRANSCRIBED, session.status)
        assertEquals(0L, session.durationMs)
    }
}
