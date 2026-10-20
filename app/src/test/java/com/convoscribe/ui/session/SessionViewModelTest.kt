package com.convoscribe.ui.session

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.convoscribe.R
import com.convoscribe.audio.AudioExporter
import com.convoscribe.audio.AudioPlayer
import com.convoscribe.audio.FakeAudioPlayer
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.TranscriptMark
import com.convoscribe.ui.UiText
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [SessionViewModel]. The repository is real (the
 * ViewModel's only job is to fan-in the three Room flows) but the
 * database is an in-memory Room database under Robolectric, so
 * nothing touches the device.
 *
 * The `stateIn(WhileSubscribed)` flow only emits once a collector
 * subscribes — `vm.uiState.first { condition }` waits for the
 * real Room snapshot and skips the `SessionUiState()` initial.
 *
 * Build view models with [viewModel] so [tearDown] can stop them: their
 * coroutines run on `viewModelScope`, outside `runTest`, and Room and
 * the exporter resume them onto `Dispatchers.Main` from their own
 * threads. Left running, one lands while `resetMain()` is swapping Main
 * out and fails the test ("Dispatchers.Main is used concurrently with
 * setting it").
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var recordingsDir: File
    private lateinit var repo: SessionRepository
    private val viewModels = mutableListOf<SessionViewModel>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        recordingsDir = File(context.filesDir, "session-vm-test-${System.nanoTime()}")
        recordingsDir.mkdirs()
        repo = SessionRepository(db, recordingsDir)
    }

    @After
    fun tearDown() {
        clearViewModels()
        if (::db.isInitialized) db.close()
        Dispatchers.resetMain()
        if (recordingsDir.exists()) {
            recordingsDir.deleteRecursively()
        }
    }

    private fun viewModel(
        id: Long,
        playerFactory: ((CoroutineScope) -> AudioPlayer)? = null,
        audioExporter: AudioExporter? = null,
        cueAtMs: Long? = null
    ) = SessionViewModel(
        sessionId = id,
        repository = repo,
        playerFactory = playerFactory,
        audioExporter = audioExporter,
        cueAtMs = cueAtMs
    ).also { viewModels += it }

    /**
     * Cancel every view model's scope and wait until its coroutines have
     * finished, while Main is still the test dispatcher. `runTest` drives
     * the dispatcher and waits for the background threads to hand back.
     */
    private fun clearViewModels() = runTest(dispatcher) {
        val jobs = viewModels.map { it.viewModelScope.coroutineContext.job }
        jobs.forEach { it.cancel() }
        jobs.joinAll()
    }

    @Test
    fun unknownSessionId_yieldsNotFound() = runTest(dispatcher) {
        // No sessions inserted — the id `9999` is unknown.
        val vm = viewModel(9999L)

        val state = vm.uiState.first { it.notFound }
        assertTrue(state.notFound)
        assertEquals("", state.title)
        assertNull(state.status)
        assertTrue(state.lines.isEmpty())
        assertTrue(state.speakers.isEmpty())
    }

    @Test
    fun transcriptLinesResolveSpeakerLabels() = runTest(dispatcher) {
        val id = repo.createSession(title = "demo", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        repo.addSegment(sessionId = id, startMs = 3_000, endMs = 4_000, text = "Hi")
        // Apply a diarization so two speakers appear with default
        // labels. The assignments map is keyed by the segment ids
        // we just got back from `addSegment`.
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = segments.associate { it.id to it.text.hashCode().let { h -> h and 1 } },
            speakerCount = 2
        )

        val vm = viewModel(id)
        val state = vm.uiState.first { it.lines.size == 2 }
        assertEquals("demo", state.title)
        assertEquals(SessionStatus.DIARIZED, state.status)
        assertEquals(2, state.lines.size)
        assertEquals(0L, state.lines[0].startMs)
        assertEquals("Hello", state.lines[0].text)
        // Labels came from the speakers table; "Speaker 1" / "Speaker 2"
        // are the defaults produced by `applyDiarization`.
        assertEquals("Speaker 1", state.lines[0].speaker)
        assertEquals("Speaker 2", state.lines[1].speaker)
        assertEquals(2, state.speakers.size)
    }

    @Test
    fun uiState_exposesLanguageParticipantsAndEngine_andSetParticipantsSavesThem() = runTest(dispatcher) {
        val id = repo.createSession(title = "info", language = "fr", numSpeakers = 2, participants = listOf("Alice"))
        repo.recordEngine(id, engine = "cpu", model = "asr-fr")
        val vm = viewModel(id)

        val state = vm.uiState.first { it.title == "info" && it.asrEngine != null }
        assertEquals("fr", state.language)
        assertEquals(2, state.numSpeakers)
        assertEquals(listOf("Alice"), state.participants)
        assertEquals("asr-fr", state.asrModel)

        vm.setParticipants(listOf("Alice", "Bob"))
        assertEquals(listOf("Alice", "Bob"), vm.uiState.first { it.participants?.size == 2 }.participants)
        vm.setParticipants(null)
        assertNull(vm.uiState.first { it.participants == null }.participants)
    }

    @Test
    fun transcriptLinesAreEmptyWhenSessionHasNoSegments() = runTest(dispatcher) {
        val id = repo.createSession(title = "empty", language = "en")
        val vm = viewModel(id)

        val state = vm.uiState.first { it.title == "empty" && !it.notFound }
        assertEquals("empty", state.title)
        assertTrue(state.lines.isEmpty())
    }

    @Test
    fun renamingSpeaker_updatesUiStateLinesLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "rename", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = viewModel(id)
        // First emission carries the default label "Speaker 1".
        val before = vm.uiState.first { it.lines.size == 1 && it.lines[0].speaker == "Speaker 1" }
        assertEquals("Speaker 1", before.lines[0].speaker)

        // Rename the speaker; the ViewModel must re-emit because
        // the speakers flow Room observes is part of the
        // `combine` block.
        repo.renameSpeaker(sessionId = id, speakerIndex = 0, label = "Alice")

        val after = vm.uiState.first { it.lines[0].speaker == "Alice" }
        assertEquals("Alice", after.lines[0].speaker)
        // Text content is unchanged.
        assertEquals("Hello", after.lines[0].text)
    }

    @Test
    fun unlabelledSegmentAppearsWithoutSpeakerLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "unlabelled", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        // Deliberately do not run diarization — `speakerIndex` stays
        // null on the only segment.
        val vm = viewModel(id)

        val state = vm.uiState.first { it.lines.size == 1 }
        assertNull(state.lines[0].speaker)
        assertEquals("Hello", state.lines[0].text)
        assertTrue(state.speakers.isEmpty())
    }

    @Test
    fun editLineText_updatesLinesAndShareText_flagsTheSession_andCanBeUndone() = runTest(dispatcher) {
        val id = repo.createSession(title = "edit", language = "en")
        val line = repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello wrld")
        val vm = viewModel(id)
        vm.uiState.first { it.lines.size == 1 }

        vm.editLineText(line, "Hello world")
        val done = vm.editDone.first()
        assertEquals(UiText.res(R.string.session_line_done_text), done.message)
        val edited = vm.uiState.first { it.hasEdits }
        assertEquals("Hello world", edited.lines.single().text)
        assertTrue(edited.lines.single().edited)
        assertEquals("[00:00] Hello world", vm.transcriptText())

        vm.undoEdit(done.undo)
        val undone = vm.uiState.first { !it.hasEdits }
        assertEquals("Hello wrld", undone.lines.single().text)
    }

    @Test
    fun undoEdit_ignoresAnOlderEdit() = runTest(dispatcher) {
        val id = repo.createSession(title = "edit", language = "en")
        val first = repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "one")
        val second = repo.addSegment(sessionId = id, startMs = 1_000, endMs = 2_000, text = "two")
        val vm = viewModel(id)
        vm.uiState.first { it.lines.size == 2 }

        vm.deleteLine(first)
        val older = vm.editDone.first()
        vm.editLineText(second, "deux")
        vm.editDone.first()
        vm.undoEdit(older.undo)
        advanceUntilIdle()

        assertEquals(listOf("deux"), repo.getSegments(id).map { it.text })
    }

    @Test
    fun transcriptTextExposesShareReadyString() = runTest(dispatcher) {
        val id = repo.createSession(title = "share", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        repo.addSegment(sessionId = id, startMs = 3_000, endMs = 4_000, text = "Hi")
        val vm = viewModel(id)
        // Wait for the lines to be available before reading the
        // cached value.
        vm.uiState.first { it.lines.size == 2 }

        // Unlabelled segments (no diarization has been applied yet)
        // are exported with the timestamp prefix only.
        assertEquals(
            "[00:00] Hello\n[00:03] Hi",
            vm.transcriptText()
        )
        assertFalse(vm.transcriptText().isBlank())
    }

    @Test
    fun marks_areInTheStateAndTheShareText_andCanBeEditedAndDeleted() = runTest(dispatcher) {
        val id = repo.createSession(title = "marks", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        repo.addSegment(sessionId = id, startMs = 3_000, endMs = 4_000, text = "Decided")
        repo.addMark(id, atMs = 3_500L, note = "decision")
        val vm = viewModel(id)
        val mark = vm.uiState.first { it.marks.isNotEmpty() && it.lines.size == 2 }.marks.single()
        assertEquals(TranscriptMark(mark.id, 3_500L, "decision"), mark)
        assertEquals("[00:00] Hello\n[00:03] \u2691 Mark: decision\n[00:03] Decided", vm.transcriptText())

        vm.editMarkNote(mark, "  ")
        assertNull(vm.uiState.first { it.marks.singleOrNull()?.note == null }.marks.single().note)

        vm.deleteMark(mark)
        vm.uiState.first { it.marks.isEmpty() }
        assertEquals("[00:00] Hello\n[00:03] Decided", vm.transcriptText())
    }

    @Test
    fun statusBannerInputs_coverDiarizingAndFailureStates() = runTest(dispatcher) {
        // DIARIZING: session row exists but no speakers/segments
        // have been re-rendered yet. The screen keys the banner off
        // `status`, so we only need the session row to land.
        val diarizingId = repo.createSession(title = "running", language = "en")
        repo.setStatus(diarizingId, SessionStatus.DIARIZING)
        val diarizingVm = viewModel(diarizingId)
        val diarizingState = diarizingVm.uiState.first { it.status == SessionStatus.DIARIZING }
        assertEquals(SessionStatus.DIARIZING, diarizingState.status)

        // DIARIZATION_FAILED: status flips after a diarizer error.
        val failedId = repo.createSession(title = "failed", language = "en")
        repo.setStatus(failedId, SessionStatus.DIARIZATION_FAILED)
        val failedVm = viewModel(failedId)
        val failedState = failedVm.uiState.first { it.status == SessionStatus.DIARIZATION_FAILED }
        assertEquals(SessionStatus.DIARIZATION_FAILED, failedState.status)
    }

    @Test
    fun renameSpeaker_updatesLinesLabel() = runTest(dispatcher) {
        val id = repo.createSession(title = "rename", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "Hello")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = viewModel(id)
        // Wait for the default label to land.
        vm.uiState.first { it.lines.size == 1 && it.lines[0].speaker == "Speaker 1" }

        // Rename through the ViewModel — this is the path the
        // screen exercises. Surrounding whitespace must not
        // survive; the speakers flow re-emits with "Alice".
        vm.renameSpeaker(index = 0, label = "  Alice ")
        val state = vm.uiState.first { it.lines[0].speaker == "Alice" }
        assertEquals("Alice", state.lines[0].speaker)
    }

    @Test
    fun renameSession_updatesTitle() = runTest(dispatcher) {
        val id = repo.createSession(title = "before", language = "en")
        repo.addSegment(sessionId = id, startMs = 0, endMs = 1_000, text = "x")
        val segments = repo.getSegments(id)
        repo.applyDiarization(
            sessionId = id,
            assignments = mapOf(segments[0].id to 0),
            speakerCount = 1
        )

        val vm = viewModel(id)
        vm.uiState.first { it.title == "before" }

        // Same trim rule as the repository — the ViewModel
        // delegates the validation, so the test only has to
        // prove the round-trip.
        vm.renameSession(title = "  Standup  ")
        val state = vm.uiState.first { it.title == "Standup" }
        assertEquals("Standup", state.title)
    }

    // ---- playback -------------------------------------------------

    private suspend fun finishedSessionWithLines(): Long {
        val id = repo.createSession(title = "play", language = "en")
        repo.addSegment(sessionId = id, startMs = 1_000, endMs = 3_000, text = "First")
        repo.addSegment(sessionId = id, startMs = 5_000, endMs = 7_000, text = "Second")
        repo.finishRecording(id, durationMs = 8_000)
        return id
    }

    private fun playbackVm(id: Long, player: FakeAudioPlayer) = viewModel(id, playerFactory = { player })

    @Test
    fun playback_opensTheRecordingOnceTheSessionIsFinished() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)

        vm.uiState.first { it.lines.size == 2 }
        advanceUntilIdle()

        assertEquals(listOf(File(repo.audioPath(id))), player.prepared)
        assertTrue(vm.playerState.value.ready)
    }

    @Test
    fun playback_ofACleanedSession_playsTheCleanedRecordingAndSwitchesKeepingThePosition() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val original = File(repo.audioPath(id)).apply { writeBytes(ByteArray(100)) }
        val work = File(original.parentFile, "work.wav").apply { writeBytes(ByteArray(100)) }
        repo.replaceTranscript(
            id,
            "en",
            listOf(com.convoscribe.data.SegmentPiece(0, 1_000, "clean", emptyList(), null)),
            "cpu",
            "asr",
            com.convoscribe.data.CleanedAudio(work, "enh-test")
        )
        val cleaned = File(original.parentFile, "$id.clean.wav")
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)
        vm.uiState.first { it.cleanedWith == "enh-test" }
        advanceUntilIdle()
        assertEquals(listOf(cleaned), player.prepared)

        vm.seekTo(4_000L)
        vm.setPlayCleaned(false)
        advanceUntilIdle()
        assertEquals(listOf(cleaned, original), player.prepared)
        assertEquals(4_000L, vm.playerState.value.positionMs)

        vm.setPlayCleaned(true)
        advanceUntilIdle()
        vm.removeCleaning()
        vm.uiState.first { it.cleanedWith == null }
        advanceUntilIdle()
        assertFalse(cleaned.exists())
        assertEquals(original, player.prepared.last())
    }

    @Test
    fun playback_isNotOpenedWhileTheSessionIsStillRecording() = runTest(dispatcher) {
        val id = repo.createSession(title = "live", language = "en")
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)

        vm.uiState.first { it.title == "live" }
        advanceUntilIdle()

        assertTrue(player.prepared.isEmpty())
        assertFalse(vm.playerState.value.ready)
    }

    @Test
    fun tappingALine_seeksToItsStartAndPlays() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)
        val lines = vm.uiState.first { it.lines.size == 2 }.lines
        advanceUntilIdle()

        vm.playLine(lines[1])

        assertEquals(5_000L, vm.playerState.value.positionMs)
        assertTrue(vm.playerState.value.isPlaying)
    }

    @Test
    fun cue_positionsThePlayerOnceReady_withoutPlaying() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val player = FakeAudioPlayer()
        val vm = viewModel(id, playerFactory = { player }, cueAtMs = 5_000L)
        // Nothing is applied before the recording is open.
        assertEquals(0L, player.state.value.positionMs)

        vm.uiState.first { it.lines.size == 2 }
        advanceUntilIdle()

        assertTrue(vm.playerState.value.ready)
        assertEquals(5_000L, vm.playerState.value.positionMs)
        assertFalse(vm.playerState.value.isPlaying)
        assertEquals(1, vm.activeLineIndex.first { it != null })
    }

    @Test
    fun activeLine_followsThePlayerPosition() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)
        val lines = vm.uiState.first { it.lines.size == 2 }.lines
        advanceUntilIdle()
        // Nothing has been played yet, so nothing is highlighted.
        assertNull(vm.activeLineIndex.first())

        vm.playLine(lines[0])
        assertEquals(0, vm.activeLineIndex.first { it != null })

        player.seekTo(5_500)
        assertEquals(1, vm.activeLineIndex.first { it == 1 })
    }

    @Test
    fun scrollingByHand_suspendsFollowUntilASeekOrTheFollowButton() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = playbackVm(id, FakeAudioPlayer())
        advanceUntilIdle()
        assertTrue(vm.following.value)

        vm.onUserScrolled()
        assertFalse(vm.following.value)
        vm.resumeFollow()
        assertTrue(vm.following.value)

        vm.onUserScrolled()
        vm.seekTo(2_000)
        assertTrue(vm.following.value)

        vm.onUserScrolled()
        vm.skip(10_000)
        assertTrue(vm.following.value)
    }

    @Test
    fun stopPlayback_pausesButKeepsThePosition() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val player = FakeAudioPlayer()
        val vm = playbackVm(id, player)
        advanceUntilIdle()
        vm.seekTo(4_000)
        vm.togglePlayback()
        assertTrue(vm.playerState.value.isPlaying)

        vm.stopPlayback()

        assertFalse(vm.playerState.value.isPlaying)
        assertEquals(4_000L, vm.playerState.value.positionMs)
    }

    @Test
    fun cycleSpeed_goesThroughTheSpeedsAndWraps() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = playbackVm(id, FakeAudioPlayer())
        advanceUntilIdle()

        vm.cycleSpeed()
        assertEquals(1.5f, vm.playerState.value.speed)
        vm.cycleSpeed()
        assertEquals(2f, vm.playerState.value.speed)
        vm.cycleSpeed()
        assertEquals(1f, vm.playerState.value.speed)
    }

    @Test
    fun playback_isNoopWithoutAPlayer() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = viewModel(id)

        vm.togglePlayback()
        vm.seekTo(1_000)
        vm.stopPlayback()

        assertFalse(vm.playerState.value.ready)
    }

    // ---- audio export ---------------------------------------------

    private fun exportVm(id: Long, encode: (File, File, (Float) -> Unit, () -> Unit) -> Unit) = viewModel(
        id,
        audioExporter = AudioExporter(
            exportDir = File(recordingsDir, "exports"),
            encode = encode,
            openOutput = { java.io.ByteArrayOutputStream() }
        )
    )

    @Test
    fun shareAudio_emitsTheEncodedFileAndEndsIdle() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = exportVm(id) { _, out, onProgress, _ ->
            onProgress(0.5f)
            out.writeText("m4a")
        }

        vm.shareAudio()
        val file = vm.shareAudioFiles.first()

        assertEquals("m4a", file.readText())
        assertTrue(file.name.endsWith(".m4a"))
        advanceUntilIdle()
        assertEquals(ExportState.Idle, vm.exportState.value)
    }

    @Test
    fun saveAudio_writesToTheChosenLocationAndSaysSo() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = exportVm(id) { _, out, _, _ -> out.writeText("m4a") }

        vm.saveAudio(android.net.Uri.parse("content://documents/x.m4a"))

        assertEquals(UiText.res(R.string.session_audio_saved), vm.snackbar.first())
        advanceUntilIdle()
        assertEquals(ExportState.Idle, vm.exportState.value)
    }

    @Test
    fun cancelExport_stopsEncodingAndSharesNothing() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val started = java.util.concurrent.CountDownLatch(1)
        val vm = exportVm(id) { _, _, _, checkCancelled ->
            started.countDown()
            while (true) {
                Thread.sleep(5)
                checkCancelled()
            }
        }

        vm.shareAudio()
        kotlinx.coroutines.withContext(Dispatchers.IO) { started.await() }
        assertTrue(vm.exportState.value is ExportState.Encoding)
        vm.cancelExport()
        advanceUntilIdle()
        // The job ends on the IO thread; wait for it to report back.
        vm.exportState.first { it == ExportState.Idle }

        assertEquals(ExportState.Idle, vm.exportState.value)
    }

    @Test
    fun exportFailure_isReportedOnTheSnackbar() = runTest(dispatcher) {
        val id = finishedSessionWithLines()
        val vm = exportVm(id) { _, _, _, _ -> throw java.io.IOException("disk full") }

        vm.shareAudio()

        assertEquals(UiText.res(R.string.session_audio_export_failed, "disk full"), vm.snackbar.first())
        advanceUntilIdle()
        assertEquals(ExportState.Idle, vm.exportState.value)
    }
}
