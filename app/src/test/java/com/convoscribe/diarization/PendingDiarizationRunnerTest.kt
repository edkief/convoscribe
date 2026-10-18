package com.convoscribe.diarization

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The charging job's loop (issue #11): pending sessions oldest first,
 * nothing under *On demand*, giving way to other work, and waiting
 * for crash recovery.
 */
@RunWith(RobolectricTestRunner::class)
class PendingDiarizationRunnerTest {

    private lateinit var db: AppDatabase
    private lateinit var dir: File
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository

    /** Session ids in the order the engine saw them, by the speaker count each was saved with. */
    private val diarized = mutableListOf<Int>()

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(context.filesDir, "pending-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, dir)
        settings = SettingsRepository(
            context.getSharedPreferences("pending-${System.nanoTime()}", Context.MODE_PRIVATE),
            InMemorySecretStore()
        )
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    private val useCase by lazy {
        DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = DiarizerFactory {
                object : Diarizer {
                    override fun diarize(
                        samples: FloatArray,
                        numSpeakers: Int,
                        threshold: Float,
                        onProgress: (Float) -> Unit,
                        isCancelled: () -> Boolean
                    ): List<SpeakerTurn> {
                        diarized += numSpeakers
                        return listOf(SpeakerTurn(startMs = 0, endMs = 1_000, speaker = 0))
                    }
                    override fun close() = Unit
                }
            },
            settings = settings,
            readSamples = { FloatArray(16_000) }
        )
    }

    /** A pending session tagged by its speaker count [tag], so the engine call tells which one ran. */
    private suspend fun pendingSession(tag: Int, status: SessionStatus = SessionStatus.DIARIZATION_PENDING): Long {
        val id = repo.createSession(title = "s$tag", language = "en", numSpeakers = tag)
        repo.addSegment(id, startMs = 0, endMs = 1_000, text = "hello")
        repo.setStatus(id, status)
        return id
    }

    private fun runner(awaitRecovery: suspend () -> Unit = {}, busy: () -> Boolean = { false }) =
        PendingDiarizationRunner(repo, useCase, settings, awaitRecovery, busy)

    @Test
    fun run_identifiesEveryPendingSessionOldestFirst() = runTest {
        val first = pendingSession(2)
        val other = pendingSession(9, SessionStatus.DIARIZED)
        val second = pendingSession(3)
        val runner = runner()
        assertTrue(runner.hasPending())

        assertEquals(PendingDiarizationRunner.Outcome.DONE, runner.run())

        assertEquals(listOf(2, 3), diarized)
        assertEquals(SessionStatus.DIARIZED, repo.getSession(first)!!.status)
        assertEquals(SessionStatus.DIARIZED, repo.getSession(second)!!.status)
        assertEquals(SessionStatus.DIARIZED, repo.getSession(other)!!.status)
        assertFalse(runner.hasPending())
    }

    @Test
    fun run_onDemand_identifiesNothing() = runTest {
        settings.update { it.copy(speakerIdMode = "manual") }
        val id = pendingSession(2)

        assertEquals(PendingDiarizationRunner.Outcome.DISABLED, runner().run())

        assertTrue(diarized.isEmpty())
        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(id)!!.status)
    }

    @Test
    fun run_givesWayToOtherWork() = runTest {
        val id = pendingSession(2)

        assertEquals(PendingDiarizationRunner.Outcome.BUSY, runner(busy = { true }).run())

        assertTrue(diarized.isEmpty())
        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(id)!!.status)
    }

    @Test
    fun run_waitsForCrashRecovery() = runTest {
        pendingSession(2)
        val recovered = CompletableDeferred<Unit>()

        val outcome = async { runner(awaitRecovery = { recovered.await() }).run() }
        yield()
        assertTrue(diarized.isEmpty())
        recovered.complete(Unit)

        assertEquals(PendingDiarizationRunner.Outcome.DONE, outcome.await())
        assertEquals(listOf(2), diarized)
    }
}
