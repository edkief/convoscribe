package com.convoscribe.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.FileRecognizer
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TimedToken
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.asr.server.ServerAsrException
import com.convoscribe.audio.AudioCleanerFactory
import com.convoscribe.audio.StreamingCleaner
import com.convoscribe.audio.WavReader
import com.convoscribe.audio.WavWriter
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SegmentEntity
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TokenTimingCodec
import com.convoscribe.data.cleanAudioFile
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
import kotlinx.coroutines.awaitCancellation
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

    private fun useCase(
        factory: TranscriberFactory,
        cleaners: AudioCleanerFactory? = null,
        server: FileRecognizer? = null,
        serverAuto: Boolean = false,
        whisper: WhisperPass? = null,
        whisperAuto: Boolean = false
    ) = RetranscribeUseCase(
        repository = repo,
        transcriberFactory = factory,
        diarize = diarizer(),
        asrDispatcher = Dispatchers.Unconfined,
        cleanerFactory = cleaners,
        server = server,
        serverAuto = { serverAuto },
        whisper = whisper,
        whisperAuto = { whisperAuto }
    )

    /** On-device Whisper (issue #158): one line with no word times, or [failure]. */
    private class FakeWhisper(private val failure: Throwable? = null) : FileRecognizer {
        val calls = mutableListOf<String>()
        var onTranscribe: () -> Unit = {}
        override suspend fun transcribe(
            file: File,
            language: String,
            onProgress: (Float) -> Unit
        ): List<TranscriptEvent.Final> {
            calls += language
            onTranscribe()
            onProgress(0.5f)
            failure?.let { throw it }
            return listOf(TranscriptEvent.Final("Hello there.", 0, 2_000))
        }
        override fun describe() = EngineInfo("cpu", "asr-whisper-large-v3-turbo-int8")
    }

    private fun whisperPass(recognizer: FileRecognizer, live: TranscriberFactory, available: Boolean = true) =
        WhisperPass(recognizer, { available }, live, Dispatchers.Unconfined)

    /** [englishSession] whose line has word times: "hello" at 300 ms, "there" at 1.2 s. */
    private suspend fun timedSession(): Long {
        val id = repo.createSession(title = "t", language = "en")
        WavWriter(File(repo.audioPath(id))).use { it.write(ShortArray(2_000 * 16)) }
        repo.finishRecording(id, 2_000)
        repo.addSegment(id, 0, 2_000, "hello there", listOf(TimedToken(" hello", 300), TimedToken(" there", 1_200)))
        return id
    }

    /** The transcription server (issue #58): two timed lines, or [failure]. */
    private class FakeServer(private val failure: Throwable? = null) : FileRecognizer {
        val calls = mutableListOf<Pair<File, String>>()
        override suspend fun transcribe(
            file: File,
            language: String,
            onProgress: (Float) -> Unit
        ): List<TranscriptEvent.Final> {
            calls += file to language
            onProgress(0.5f)
            failure?.let { throw it }
            return listOf(
                TranscriptEvent.Final(
                    "Hello there.",
                    100,
                    900,
                    listOf(TimedToken(" Hello", 100), TimedToken(" there.", 500))
                ),
                TranscriptEvent.Final("General Kenobi.", 1_000, 1_900, listOf(TimedToken(" General", 1_000)))
            )
        }
        override fun describe() = EngineInfo("server", "parakeet-tdt-0.6b-v3")
    }

    /** A cleaner that halves every sample (issue #37). */
    private class Halving(private val available: Boolean = true) : AudioCleanerFactory {
        override fun isAvailable() = available
        override fun create(): StreamingCleaner = object : StreamingCleaner {
            override fun process(samples: FloatArray) = FloatArray(samples.size) { samples[it] / 2 }
            override fun flush() = FloatArray(0)
            override fun close() = Unit
        }
        override fun modelId() = "enh-test"
    }

    /** Records the loudest sample it was fed. */
    private class PeakTranscriber : StreamingTranscriber {
        var peak = 0f
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            samples.forEach { peak = maxOf(peak, it) }
            return emptyList()
        }
        override fun finish() = listOf(TranscriptEvent.Final("clean", 0, 2_000))
        override fun close() = Unit
    }

    /** [englishSession] whose audio is a constant 0.25. */
    private suspend fun loudSession(): Long {
        val id = englishSession()
        WavWriter(File(repo.audioPath(id))).use { it.write(ShortArray(2_000 * 16) { 8_192 }) }
        return id
    }

    private fun diarizer() = DiarizeSessionUseCase(
        repository = repo,
        diarizerFactory = DiarizerFactory {
            object : Diarizer {
                override fun diarize(
                    samples: FloatArray,
                    numSpeakers: Int,
                    threshold: Float,
                    onProgress: (Float) -> Unit,
                    isCancelled: () -> Boolean
                ) = listOf(SpeakerTurn(startMs = 0, endMs = 2_000, speaker = 4))
                override fun close() = Unit
            }
        },
        settings = settings,
        readSamples = { FloatArray(2 * 16_000) }
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
    fun onDemand_leavesTheNewTranscriptPending_andNamesCarryOverOnceDiarized() = runTest {
        settings.update { it.copy(speakerIdMode = "manual") }
        val id = englishSession()

        assertTrue(useCase(factory { FrenchTranscriber() }).run(id, "fr"))

        assertEquals(SessionStatus.DIARIZATION_PENDING, repo.getSession(id)!!.status)
        // The new lines keep the old speaker until speakers are identified.
        assertEquals(listOf("un", "deux"), repo.getSegments(id).map { it.text })
        assertEquals(listOf(0, 0), repo.getSegments(id).map { it.speakerIndex })
        assertEquals(listOf("Alice"), repo.getSpeakers(id).map { it.label })

        diarizer().run(id)

        assertEquals(SessionStatus.DIARIZED, repo.getSession(id)!!.status)
        assertEquals(listOf("Alice"), repo.getSpeakers(id).map { it.label })
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
    fun onServer_replacesTheTranscriptWithTheServersLinesAndEngine() = runTest {
        val id = englishSession()
        val factory = factory { FrenchTranscriber() }
        val server = FakeServer()
        val uc = useCase(factory, server = server)

        assertTrue(uc.run(id, "en", target = RetranscribeTarget.SERVER))

        assertTrue(factory.languages.isEmpty())
        assertEquals(listOf(File(repo.getSession(id)!!.audioPath) to "en"), server.calls)
        val session = repo.getSession(id)!!
        assertEquals("en", session.language)
        assertEquals("server", session.asrEngine)
        assertEquals("parakeet-tdt-0.6b-v3", session.asrModel)
        assertEquals(SessionStatus.DIARIZED, session.status)
        val segments = repo.getSegments(id)
        assertEquals(listOf("Hello there.", "General Kenobi."), segments.map { it.text })
        assertEquals(listOf(100L, 1_000L), segments.map { it.startMs })
        assertEquals(listOf("Alice"), repo.getSpeakers(id).map { it.label })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun onServer_failureKeepsThePreviousTranscriptAndShowsTheError() = runTest {
        val id = englishSession()
        val uc =
            useCase(factory { FrenchTranscriber() }, server = FakeServer(ServerAsrException("HTTP 502 from x: down")))

        assertFalse(uc.run(id, "en", target = RetranscribeTarget.SERVER))

        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        val state = uc.state.value
        assertTrue(state is RetranscribeState.Failed && state.message == "HTTP 502 from x: down")
    }

    @Test
    fun onServer_withoutAServerFails() = runTest {
        val id = englishSession()
        val uc = useCase(factory { FrenchTranscriber() })

        assertFalse(uc.run(id, "en", target = RetranscribeTarget.SERVER))

        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        assertTrue(uc.state.value is RetranscribeState.Failed)
    }

    @Test
    fun improveAfterStop_replacesTheTranscriptInTheSessionsLanguageWithoutIdentifyingSpeakers() = runTest {
        val id = englishSession()
        val server = FakeServer()
        val uc = useCase(factory { FrenchTranscriber() }, server = server, serverAuto = true)

        assertEquals(RetranscribeTarget.SERVER, uc.afterStopTarget(id))
        assertTrue(uc.improveAfterStop(id))

        assertEquals("en", server.calls.single().second)
        assertEquals(listOf("Hello there.", "General Kenobi."), repo.getSegments(id).map { it.text })
        assertEquals("server", repo.getSession(id)!!.asrEngine)
        // Speakers are the caller's job; the old speaker carries over meanwhile.
        assertEquals(listOf(0, 0), repo.getSegments(id).map { it.speakerIndex })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun improveAfterStop_doesNothingWhenOff() = runTest {
        val id = englishSession()
        val server = FakeServer()
        val uc = useCase(factory { FrenchTranscriber() }, server = server, serverAuto = false)

        assertNull(uc.afterStopTarget(id))
        assertFalse(uc.improveAfterStop(id))

        assertTrue(server.calls.isEmpty())
        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun improveAfterStop_neverReplacesAnEditedTranscript() = runTest {
        val id = englishSession()
        repo.editLineText(repo.getSegments(id).single().id, "hello there, corrected")
        val server = FakeServer()
        val uc = useCase(factory { FrenchTranscriber() }, server = server, serverAuto = true)

        assertNull(uc.afterStopTarget(id))
        assertFalse(uc.improveAfterStop(id))

        assertTrue(server.calls.isEmpty())
        assertEquals(listOf("hello there, corrected"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun improveAfterStop_failureKeepsTheDeviceTranscriptWithoutAnError() = runTest {
        val id = englishSession()
        val uc = useCase(
            factory { FrenchTranscriber() },
            server = FakeServer(ServerAsrException("Network error calling x: timeout")),
            serverAuto = true
        )

        assertFalse(uc.improveAfterStop(id))

        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun improveAfterStop_cancelKeepsTheDeviceTranscript() = runTest {
        val id = englishSession()
        val job = Job()
        val server = object : FileRecognizer {
            override suspend fun transcribe(
                file: File,
                language: String,
                onProgress: (Float) -> Unit
            ): List<TranscriptEvent.Final> {
                job.cancel()
                awaitCancellation()
            }
            override fun describe() = EngineInfo("server", "m")
        }
        val uc = useCase(factory { FrenchTranscriber() }, server = server, serverAuto = true)
        try {
            withContext(job) { uc.improveAfterStop(id) }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }

        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun runsOnDevice_whileTranscribingOnThePhone() = runTest {
        val id = englishSession()
        lateinit var uc: RetranscribeUseCase
        val seen = mutableListOf<Boolean>()
        uc = useCase(
            factory {
                object : StreamingTranscriber {
                    override fun accept(samples: FloatArray): List<TranscriptEvent> {
                        seen += uc.runsOnDevice
                        return emptyList()
                    }
                    override fun finish(): List<TranscriptEvent> = emptyList()
                    override fun close() = Unit
                }
            }
        )
        assertFalse(uc.runsOnDevice)

        uc.run(id, "fr")

        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it })
        assertFalse(uc.runsOnDevice)
    }

    @Test
    fun runsOnDevice_whileCleaningOnThePhone_butNotWhileTheServerTranscribes() = runTest {
        val id = loudSession()
        lateinit var uc: RetranscribeUseCase
        val cleaning = mutableListOf<Boolean>()
        val serving = mutableListOf<Boolean>()
        val cleaners = object : AudioCleanerFactory {
            override fun isAvailable() = true
            override fun create(): StreamingCleaner = object : StreamingCleaner {
                override fun process(samples: FloatArray): FloatArray {
                    cleaning += uc.runsOnDevice
                    return samples
                }
                override fun flush() = FloatArray(0)
                override fun close() = Unit
            }
            override fun modelId() = "enh-test"
        }
        val server = object : FileRecognizer {
            override suspend fun transcribe(
                file: File,
                language: String,
                onProgress: (Float) -> Unit
            ): List<TranscriptEvent.Final> {
                serving += uc.runsOnDevice
                onProgress(0.5f)
                serving += uc.runsOnDevice
                return listOf(TranscriptEvent.Final("clean", 0, 2_000))
            }
            override fun describe() = EngineInfo("server", "m")
        }
        uc = useCase(factory { FrenchTranscriber() }, cleaners = cleaners, server = server)

        assertTrue(uc.run(id, "en", clean = true, target = RetranscribeTarget.SERVER))

        assertTrue(cleaning.isNotEmpty())
        assertTrue(cleaning.all { it })
        assertEquals(listOf(false, false), serving)
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

    @Test
    fun clean_transcribesTheCleanedAudioAndKeepsItNextToTheOriginal() = runTest {
        val id = loudSession()
        val transcriber = PeakTranscriber()
        val uc = useCase(factory { transcriber }, Halving())

        assertTrue(uc.run(id, "en", clean = true))

        // Half cleaned (0.125), half original (0.25).
        assertEquals(0.1875f, transcriber.peak, 1e-4f)
        val session = repo.getSession(id)!!
        assertEquals("enh-test", session.cleanedWith)
        val original = WavReader.readMono16k(File(session.audioPath))
        val cleaned = WavReader.readMono16k(session.cleanAudioFile())
        assertEquals(original.size, cleaned.size)
        assertEquals(0.25f, original[100], 1e-4f)
        assertEquals(0.125f, cleaned[100], 1e-4f)
        assertEquals(listOf("clean"), repo.getSegments(id).map { it.text })
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".cleaning.wav") || it.name.endsWith(".mix.wav") })

        // A later plain re-transcription uses the cleaned recording too.
        val again = PeakTranscriber()
        assertTrue(useCase(factory { again }, Halving()).run(id, "en"))
        assertEquals(0.1875f, again.peak, 1e-4f)
        assertEquals("enh-test", repo.getSession(id)!!.cleanedWith)
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun clean_failureOrMissingModelLeavesTheSessionUncleaned() = runTest {
        val id = loudSession()

        val missing = useCase(factory { PeakTranscriber() }, Halving(available = false))
        assertFalse(missing.run(id, "en", clean = true))
        assertTrue(missing.state.value is RetranscribeState.Failed)

        val crashing = useCase(factory { FrenchTranscriber(failAfterMs = 500) }, Halving())
        assertFalse(crashing.run(id, "en", clean = true))

        val session = repo.getSession(id)!!
        assertNull(session.cleanedWith)
        assertFalse(session.cleanAudioFile().exists())
        assertTrue(dir.listFiles()!!.none { it.name.endsWith(".cleaning.wav") })
        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun whisper_replacesTheTranscriptWithWordTimesBorrowedFromTheOldOne() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        val uc = useCase(live, whisper = whisperPass(recognizer, live))

        assertTrue(uc.run(id, "en", target = RetranscribeTarget.WHISPER))

        assertEquals(listOf("en"), recognizer.calls)
        // The old transcript had word times: no live pass needed.
        assertTrue(live.languages.isEmpty())
        val segment = repo.getSegments(id).single()
        assertEquals("Hello there.", segment.text)
        assertEquals(listOf(300L, 1_200L), TokenTimingCodec.decode(segment.tokens).map { it.ms })
        val session = repo.getSession(id)!!
        assertEquals("cpu", session.asrEngine)
        assertEquals("asr-whisper-large-v3-turbo-int8", session.asrModel)
    }

    @Test
    fun whisper_withoutOldWordTimes_runsTheLiveEngineFirstThenSpreadsWhatItCannotMatch() = runTest {
        val id = englishSession()
        val live = factory { FrenchTranscriber() }
        val uc = useCase(live, whisper = whisperPass(FakeWhisper(), live))

        assertTrue(uc.run(id, "en", target = RetranscribeTarget.WHISPER))

        assertEquals(listOf("en"), live.languages)
        val tokens = TokenTimingCodec.decode(repo.getSegments(id).single().tokens)
        assertEquals(listOf(" Hello", " there."), tokens.map { it.text })
        assertEquals(listOf(0L, 1_000L), tokens.map { it.ms })
    }

    @Test
    fun whisper_inAnotherLanguage_doesNotBorrowTheOldWordTimes() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val uc = useCase(live, whisper = whisperPass(FakeWhisper(), live))

        assertTrue(uc.run(id, "fr", target = RetranscribeTarget.WHISPER))

        assertEquals(listOf("fr"), live.languages)
    }

    @Test
    fun whisper_runsOnTheDeviceAndSaysSo() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        lateinit var uc: RetranscribeUseCase
        val seen = mutableListOf<RetranscribeState>()
        recognizer.onTranscribe = { seen += uc.state.value }
        uc = useCase(live, whisper = whisperPass(recognizer, live))

        uc.run(id, "en", target = RetranscribeTarget.WHISPER)

        val running = seen.single() as RetranscribeState.Running
        assertTrue(running.onWhisper)
        assertTrue(running.onDevice)
    }

    @Test
    fun improveAfterStop_withWhisperOn_transcribesWithWhisper() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        val uc = useCase(live, whisper = whisperPass(recognizer, live), whisperAuto = true)

        assertEquals(RetranscribeTarget.WHISPER, uc.afterStopTarget(id))
        assertTrue(uc.improveAfterStop(id))

        assertEquals(listOf("Hello there."), repo.getSegments(id).map { it.text })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }

    @Test
    fun improveAfterStop_serverFirst_thenWhisperWhenTheServerFails() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        val uc = useCase(
            live,
            server = FakeServer(ServerAsrException("HTTP 502 from x: down")),
            serverAuto = true,
            whisper = whisperPass(recognizer, live),
            whisperAuto = true
        )

        assertEquals(RetranscribeTarget.SERVER, uc.afterStopTarget(id))
        assertTrue(uc.improveAfterStop(id))

        assertEquals(listOf("en"), recognizer.calls)
        assertEquals("asr-whisper-large-v3-turbo-int8", repo.getSession(id)!!.asrModel)
    }

    @Test
    fun improveAfterStop_serverSucceeds_whisperNotRun() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        val uc = useCase(
            live,
            server = FakeServer(),
            serverAuto = true,
            whisper = whisperPass(recognizer, live),
            whisperAuto = true
        )

        assertTrue(uc.improveAfterStop(id))

        assertTrue(recognizer.calls.isEmpty())
        assertEquals("server", repo.getSession(id)!!.asrEngine)
    }

    @Test
    fun improveAfterStop_whisperNotDownloaded_doesNothing() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val recognizer = FakeWhisper()
        val uc = useCase(live, whisper = whisperPass(recognizer, live, available = false), whisperAuto = true)

        assertNull(uc.afterStopTarget(id))
        assertFalse(uc.improveAfterStop(id))
        assertTrue(recognizer.calls.isEmpty())
    }

    @Test
    fun improveAfterStop_whisperFailureKeepsTheDeviceTranscriptWithoutAnError() = runTest {
        val id = timedSession()
        val live = factory { FrenchTranscriber() }
        val failing = whisperPass(FakeWhisper(IllegalStateException("oom")), live)
        val uc = useCase(live, whisper = failing, whisperAuto = true)

        assertFalse(uc.improveAfterStop(id))

        assertEquals(listOf("hello there"), repo.getSegments(id).map { it.text })
        assertEquals(RetranscribeState.Idle, uc.state.value)
    }
}
