package com.convoscribe.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.WavWriter
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.diarization.DiarizeSessionUseCase
import com.convoscribe.diarization.Diarizer
import com.convoscribe.diarization.DiarizerFactory
import com.convoscribe.diarization.SpeakerTurn
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Re-transcription replaces the transcript only once the whole file
 * is done: success swaps it and re-diarizes, failure or cancel leaves
 * the previous transcript and language as they were.
 */
@RunWith(RobolectricTestRunner::class)
class RetranscribeUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var dir: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        dir = File(context.filesDir, "retranscribe-${System.nanoTime()}").apply { mkdirs() }
        repo = SessionRepository(db, dir)
        settings = SettingsRepository(
            context.getSharedPreferences("retranscribe-${System.nanoTime()}", Context.MODE_PRIVATE),
            InMemorySecretStore()
        )
    }

    @After
    fun tearDown() {
        db.close()
        dir.deleteRecursively()
    }

    /** An English session with 2 s of audio, one line by a speaker named Alice. */
    private suspend fun englishSession(): Long {
        val id = repo.createSession(title = "t", language = "en")
        WavWriter(File(repo.audioPath(id))).use { it.write(ShortArray(2_000 * 16)) }
        repo.finishRecording(id, 2_000)
        repo.addSegment(id, startMs = 0, endMs = 2_000, text = "hello there")
        val segment = repo.getSegments(id).single()
        repo.applyDiarization(id, mapOf(segment.id to 0), speakerCount = 1, labels = mapOf(0 to "Alice"))
        return id
    }

    /** One final per second of audio ("un", "deux"), or throws after [failAfterMs]. */
    private class FrenchTranscriber(private val failAfterMs: Long? = null) : StreamingTranscriber {
        private var samples = 0L
        private var emitted = 0
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            this.samples += samples.size
            val ms = this.samples / 16
            if (failAfterMs != null && ms >= failAfterMs) error("engine crashed")
            if (ms / 1000 > emitted) {
                emitted++
                return listOf(TranscriptEvent.Final(if (emitted == 1) "un" else "deux", (emitted - 1) * 1000L, ms))
            }
            return emptyList()
        }
        override fun finish(): List<TranscriptEvent> = emptyList()
        override fun close() = Unit
    }

    private fun factory(transcriber: () -> StreamingTranscriber) = object : TranscriberFactory {
        var languages = mutableListOf<String>()
        override fun create(language: String): StreamingTranscriber {
            languages += language
            return transcriber()
        }
        override fun describe(language: String) = EngineInfo("cpu", "asr-$language")
    }

    private fun useCase(factory: TranscriberFactory) = RetranscribeUseCase(
        repository = repo,
        transcriberFactory = factory,
        diarize = DiarizeSessionUseCase(
            repository = repo,
            diarizerFactory = DiarizerFactory {
                object : Diarizer {
                    override fun diarize(
                        samples: FloatArray,
                        numSpeakers: Int,
                        threshold: Float,
                        onProgress: (Float) -> Unit
                    ) = listOf(SpeakerTurn(startMs = 0, endMs = 2_000, speaker = 4))
                    override fun close() = Unit
                }
            },
            settings = settings,
            readSamples = { FloatArray(2 * 16_000) }
        ),
        asrDispatcher = Dispatchers.Unconfined
    )

    @Test
    fun success_replacesTranscriptLanguageAndEngine_thenReDiarizesKeepingNames() = runTest {
        val id = englishSession()
        val factory = factory { FrenchTranscriber() }
        val uc = useCase(factory)

        assertTrue(uc.run(id, "fr"))

        assertEquals(listOf("fr"), factory.languages)
        val session = repo.getSession(id)!!
        assertEquals("fr", session.language)
        assertEquals("cpu", session.asrEngine)
        assertEquals("asr-fr", session.asrModel)
        assertEquals(SessionStatus.DIARIZED, session.status)
        assertEquals(listOf("un", "deux"), repo.getSegments(id).map { it.text })
        // Alice's name carried over through the new diarization.
        assertEquals(listOf("Alice"), repo.getSpeakers(id).map { it.label })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun failure_keepsThePreviousTranscript() = runTest {
        val id = englishSession()
        val uc = useCase(factory { FrenchTranscriber(failAfterMs = 1_500) })

        assertFalse(uc.run(id, "fr"))

        val session = repo.getSession(id)!!
        assertEquals("en", session.language)
        assertEquals(SessionStatus.DIARIZED, session.status)
        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        val state = uc.state.value
        assertTrue(state is RetranscribeState.Failed && state.message == "engine crashed")
        uc.acknowledgeFailure()
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun cancel_keepsThePreviousTranscript() = runTest {
        val id = englishSession()
        val job = Job()
        val uc = useCase(
            factory {
                object : StreamingTranscriber {
                    override fun accept(samples: FloatArray): List<TranscriptEvent> {
                        job.cancel()
                        return emptyList()
                    }
                    override fun finish(): List<TranscriptEvent> = emptyList()
                    override fun close() = Unit
                }
            }
        )
        try {
            withContext(job) { uc.run(id, "fr") }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals("en", repo.getSession(id)!!.language)
        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun dominantSpeaker_picksTheMostOverlappingLabelledLine() {
        fun seg(start: Long, end: Long, speaker: Int?) =
            SegmentEntity(sessionId = 1, startMs = start, endMs = end, text = "", speakerIndex = speaker)
        val old = listOf(seg(0, 1_000, 0), seg(1_000, 3_000, 1), seg(3_000, 4_000, null))
        assertEquals(1, RetranscribeUseCase.dominantSpeaker(old, 800, 2_000))
        assertEquals(0, RetranscribeUseCase.dominantSpeaker(old, 0, 900))
        assertNull(RetranscribeUseCase.dominantSpeaker(old, 3_200, 3_800))
    }
}
