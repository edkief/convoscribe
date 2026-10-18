package com.convoscribe.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.asr.EngineInfo
import com.convoscribe.asr.StreamingTranscriber
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.asr.TranscriptEvent
import com.convoscribe.audio.AudioDecoder
import com.convoscribe.audio.AudioImportException
import com.convoscribe.audio.PcmBlock
import com.convoscribe.audio.WavReader
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.diarization.DiarizeSessionUseCase
import com.convoscribe.diarization.Diarizer
import com.convoscribe.diarization.DiarizerFactory
import com.convoscribe.diarization.SpeakerTurn
import com.convoscribe.settings.InMemorySecretStore
import com.convoscribe.settings.SettingsRepository
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * File import (issue #3) end to end with a fake decoder and
 * transcriber: the decoded audio becomes the session's 16 kHz WAV,
 * transcript times match positions in the file, and a failed or
 * cancelled import leaves no session and no files.
 */
@RunWith(RobolectricTestRunner::class)
class ImportAudioUseCaseTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository
    private lateinit var settings: SettingsRepository
    private lateinit var recordings: File
    private lateinit var work: File

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        recordings = File(context.filesDir, "import-rec-${System.nanoTime()}").apply { mkdirs() }
        work = File(context.cacheDir, "import-work-${System.nanoTime()}")
        repo = SessionRepository(db, recordings)
        settings = SettingsRepository(
            context.getSharedPreferences("import-${System.nanoTime()}", Context.MODE_PRIVATE),
            InMemorySecretStore()
        )
    }

    @After
    fun tearDown() {
        db.close()
        recordings.deleteRecursively()
        work.deleteRecursively()
    }

    /**
     * Three seconds of 44.1 kHz stereo, silent except a 440 Hz tone
     * from 1 s to 2 s, in 100 ms blocks.
     */
    private class ToneDecoder(private val failWith: Throwable? = null, private val seconds: Int = 3) : AudioDecoder {
        var decoded: File? = null
        override suspend fun decode(file: File, onBlock: suspend (PcmBlock) -> Unit, onProgress: (Float) -> Unit) {
            decoded = file
            failWith?.let { throw it }
            val rate = 44_100
            val frames = rate * seconds
            val block = rate / 10
            for (start in 0 until frames step block) {
                val n = minOf(block, frames - start)
                val samples = FloatArray(n * 2) { i ->
                    val frame = start + i / 2
                    if (frame in rate until 2 * rate) 0.5f * sin(2 * PI * 440 * frame / rate).toFloat() else 0f
                }
                onBlock(PcmBlock(samples, channels = 2, sampleRate = rate))
                onProgress((start + n).toFloat() / frames)
            }
        }
    }

    /** One final per stretch of sound, timed on the samples it was fed. */
    private class SoundTranscriber(private val onAccept: () -> Unit = {}) : StreamingTranscriber {
        private var samples = 0L
        private var soundStart: Long? = null
        override fun accept(samples: FloatArray): List<TranscriptEvent> {
            onAccept()
            val startMs = this.samples / 16
            this.samples += samples.size
            val loud = sqrt(samples.sumOf { (it * it).toDouble() } / samples.size) > 0.05
            val open = soundStart
            return when {
                loud && open == null -> {
                    soundStart = startMs
                    emptyList()
                }
                !loud && open != null -> {
                    soundStart = null
                    listOf(TranscriptEvent.Final("tone", open, startMs))
                }
                else -> emptyList()
            }
        }
        override fun finish(): List<TranscriptEvent> = emptyList()
        override fun close() = Unit
    }

    private fun factory(transcriber: () -> StreamingTranscriber) = object : TranscriberFactory {
        val languages = mutableListOf<String>()
        override fun create(language: String): StreamingTranscriber {
            languages += language
            return transcriber()
        }
        override fun describe(language: String) = EngineInfo("cpu", "asr-$language")
    }

    private fun useCase(decoder: AudioDecoder, factory: TranscriberFactory, language: String = "fr") =
        ImportAudioUseCase(
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
                        ) = listOf(SpeakerTurn(startMs = 0, endMs = 3_000, speaker = 0))
                        override fun close() = Unit
                    }
                },
                settings = settings
            ),
            decoder = decoder,
            workDir = work,
            defaultOptions = { ConversationOptions(language = language, numSpeakers = 2) },
            asrDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined
        )

    private fun source(name: String? = "voice-note.ogg", bytes: ByteArray = ByteArray(1_000) { it.toByte() }) =
        ImportSource(name, bytes.size.toLong()) { ByteArrayInputStream(bytes) }

    private suspend fun sessionCount() = repo.observeAllSessions().first().size

    private fun assertNoFilesLeft() {
        assertTrue("work dir: ${work.list()?.toList()}", work.list().isNullOrEmpty())
        assertTrue("recordings: ${recordings.list()?.toList()}", recordings.list().isNullOrEmpty())
    }

    @Test
    fun import_createsADiarizedSessionWithTheConvertedAudioAndFileTimes() = runTest {
        val decoder = ToneDecoder()
        val factory = factory { SoundTranscriber() }
        val uc = useCase(decoder, factory)

        val id = uc.run(source())!!

        val session = repo.getSession(id)!!
        assertEquals("voice-note", session.title)
        assertEquals("fr", session.language)
        assertEquals(listOf("fr"), factory.languages)
        assertEquals(3_000L, session.durationMs)
        assertEquals(2, session.numSpeakers)
        assertEquals("asr-fr", session.asrModel)
        assertEquals(SessionStatus.DIARIZED, session.status)
        // The recording is the decoded audio, converted to 16 kHz mono.
        val wav = File(session.audioPath)
        assertEquals(File(recordings, "$id.wav"), wav)
        assertEquals(48_000L, WavReader.samples16k(wav))
        // The line sits where the sound is in the file.
        val segments = repo.getSegments(id)
        assertEquals(listOf("tone"), segments.map { it.text })
        assertEquals(1_000L to 2_000L, segments.single().startMs to segments.single().endMs)
        assertEquals(listOf(0), segments.map { it.speakerIndex })
        assertEquals(1, repo.getSpeakers(id).size)
        // The copy of the source was decoded, not the source itself.
        assertEquals(File(work, "source"), decoder.decoded)
        assertTrue(work.list().isNullOrEmpty())
        assertEquals(id, uc.createdSessionId.value)
        assertEquals(ImportState.Idle, uc.state.value)
        uc.consumeCreated()
        assertNull(uc.createdSessionId.value)
    }

    @Test
    fun import_withoutANameIsTitledByDate() = runTest {
        val id = useCase(ToneDecoder(), factory { SoundTranscriber() }).run(source(name = null))!!
        assertTrue(repo.getSession(id)!!.title.startsWith("Import "))
    }

    @Test
    fun undecodableFile_failsWithItsReasonAndLeavesNothing() = runTest {
        val uc = useCase(
            ToneDecoder(failWith = AudioImportException(AudioImportException.Reason.NO_AUDIO_TRACK)),
            factory { SoundTranscriber() }
        )

        assertNull(uc.run(source()))

        assertEquals(
            ImportState.Failed("voice-note.ogg", AudioImportException.Reason.NO_AUDIO_TRACK, null),
            uc.state.value
        )
        assertEquals(0, sessionCount())
        assertNoFilesLeft()
        uc.acknowledgeFailure()
        assertEquals(ImportState.Idle, uc.state.value)
    }

    @Test
    fun fileWithNoAudio_failsAsEmpty() = runTest {
        val uc = useCase(ToneDecoder(seconds = 0), factory { SoundTranscriber() })
        assertNull(uc.run(source()))
        assertEquals(AudioImportException.Reason.EMPTY, (uc.state.value as ImportState.Failed).reason)
        assertEquals(0, sessionCount())
        assertNoFilesLeft()
    }

    @Test
    fun unreadableSource_failsAsUnreadable() = runTest {
        val uc = useCase(ToneDecoder(), factory { SoundTranscriber() })
        assertNull(uc.run(ImportSource("gone.mp3", null) { throw FileNotFoundException("gone") }))
        assertEquals(AudioImportException.Reason.UNREADABLE, (uc.state.value as ImportState.Failed).reason)
        assertNoFilesLeft()
    }

    @Test
    fun missingModel_failsWithTheEngineMessage() = runTest {
        val uc = useCase(ToneDecoder(), TranscriberFactory { error("Model not installed: asr-fr") })
        assertNull(uc.run(source()))
        assertEquals(ImportState.Failed("voice-note.ogg", null, "Model not installed: asr-fr"), uc.state.value)
        assertEquals(0, sessionCount())
        assertNoFilesLeft()
    }

    @Test
    fun cancelDuringTranscription_leavesNoSessionAndNoFiles() = runTest {
        val job = Job()
        val uc = useCase(ToneDecoder(), factory { SoundTranscriber(onAccept = { job.cancel() }) })
        try {
            withContext(job) { uc.run(source()) }
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(0, sessionCount())
        assertNoFilesLeft()
        assertEquals(ImportState.Idle, uc.state.value)
    }

    @Test
    fun cleanUp_removesFilesOfAnInterruptedImport() {
        work.mkdirs()
        File(work, "source").writeText("x")
        File(work, "decoded.wav").writeText("x")
        useCase(ToneDecoder(), factory { SoundTranscriber() }).cleanUp()
        assertTrue(work.list().isNullOrEmpty())
    }
}
