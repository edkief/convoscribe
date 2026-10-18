package com.convoscribe.ui.screenshots

import android.app.Application
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.convoscribe.audio.PlayerState
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.TranscriptLine
import com.convoscribe.enginecheck.DeviceInfo
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.ui.enginecheck.EngineCheckBody
import com.convoscribe.ui.enginecheck.EngineCheckUiState
import com.convoscribe.ui.home.HomeBody
import com.convoscribe.ui.home.SessionRow
import com.convoscribe.ui.home.sessionSubtitle
import com.convoscribe.ui.record.RecordBody
import com.convoscribe.ui.session.AskUiState
import com.convoscribe.ui.session.ExportState
import com.convoscribe.ui.session.PlaybackActions
import com.convoscribe.ui.session.SessionBody
import com.convoscribe.ui.session.SummaryUiState
import com.convoscribe.ui.settings.SettingsActions
import com.convoscribe.ui.settings.SettingsBody
import com.convoscribe.ui.settings.SettingsForm
import com.convoscribe.ui.settings.SettingsUiState
import com.convoscribe.ui.setup.SetupBody
import com.convoscribe.ui.setup.SetupUiState
import com.convoscribe.ui.speakers.EnrollmentPhase
import com.convoscribe.ui.speakers.SpeakersBody
import com.convoscribe.ui.theme.ConvoScribeTheme
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.ZoneOffset
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every screen body with fake state, once per [Variant]
 * (device size, light / dark, font scale). A normal test run only
 * composes the screens, which catches crashes in the layouts; with
 * `-Proborazzi.test.record=true` each test also writes a PNG to
 * `app/build/outputs/roborazzi/<variant>/<screen>.png`. These are
 * the before/after images attached to UI pull requests; nothing is
 * compared against committed goldens.
 *
 * A plain [Application] replaces `ConvoApp`, so no database, model
 * store or native library is touched.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class)
class ScreenshotTest(private val variant: Variant) {

    /**
     * One rendering configuration. [size] is the `w…dp-h…dp` qualifier
     * and [density] the density bucket; night mode sits between them
     * in Robolectric's qualifier order.
     */
    data class Variant(
        val name: String,
        val size: String,
        val density: String,
        val dark: Boolean = false,
        val fontScale: Float = 1f
    ) {
        val qualifiers: String get() = "$size-${if (dark) "night" else "notnight"}-$density"

        override fun toString(): String = name
    }

    /** Applies the variant's configuration before the compose rule starts its activity. */
    @get:Rule(order = 0)
    val configuration = TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                RuntimeEnvironment.setQualifiers(variant.qualifiers)
                RuntimeEnvironment.setFontScale(variant.fontScale)
                base.evaluate()
            }
        }
    }

    @get:Rule(order = 1)
    val compose = createComposeRule()

    private fun capture(screen: String, content: @Composable () -> Unit) {
        compose.setContent {
            ConvoScribeTheme(darkTheme = variant.dark) {
                content()
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${variant.name}/$screen.png")
    }

    @Test
    fun home() = capture("home") {
        HomeBody(
            sessions = SESSIONS,
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeEmpty() = capture("home_empty") {
        HomeBody(
            sessions = emptyList(),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun recordIdle() = capture("record_idle") {
        RecordBody(
            phase = RecordingPhase.IDLE,
            preparingNpu = false,
            elapsedMs = 0L,
            finalLines = emptyList(),
            partialText = "",
            error = null,
            micDeniedMessage = null,
            options = ConversationOptions(language = "en", numSpeakers = -1, participants = null, title = ""),
            enrolledNames = listOf("Alice", "Bob"),
            onBack = {},
            onStart = {},
            onStop = {}
        )
    }

    @Test
    fun recordLive() = capture("record_live") {
        RecordBody(
            phase = RecordingPhase.RECORDING,
            preparingNpu = false,
            elapsedMs = 754_000L,
            finalLines = LIVE_LINES,
            partialText = "and then we can ship it on",
            error = null,
            micDeniedMessage = null,
            onBack = {},
            onStart = {},
            onStop = {}
        )
    }

    @Test
    fun sessionTranscript() = capture("session_transcript") {
        session(playing = true)
    }

    @Test
    fun sessionDiarizing() = capture("session_diarizing") {
        session(
            status = SessionStatus.DIARIZING,
            speakers = emptyList(),
            lines = TRANSCRIPT.map { it.copy(speaker = null) }
        )
    }

    @Test
    fun sessionEmpty() = capture("session_empty") {
        session(lines = emptyList(), speakers = emptyList())
    }

    @Test
    fun settings() = capture("settings") {
        SettingsBody(
            state = SettingsUiState(
                form = SettingsForm(baseUrl = "http://192.168.1.10:8000/v1", model = "llama-3.1-8b"),
                asrEngine = "cpu",
                asrLanguage = "en",
                defaultParticipants = listOf("Alice")
            ),
            actions = SettingsActions()
        )
    }

    @Test
    fun speakers() = capture("speakers") {
        SpeakersBody(
            speakers = listOf(enrolled("Alice"), enrolled("Alice"), enrolled("Bob")),
            enrollName = "Carol",
            phase = EnrollmentPhase.RECORDING,
            progress = 0.4f,
            embeddingModelInstalled = true,
            error = null,
            micGranted = true,
            onBack = {},
            onEnrollNameChange = {},
            onStartRecording = {},
            onCancel = {},
            onRequestDelete = {},
            onOpenSetup = {}
        )
    }

    @Test
    fun speakersEmpty() = capture("speakers_empty") {
        SpeakersBody(
            speakers = emptyList(),
            enrollName = "",
            phase = EnrollmentPhase.IDLE,
            progress = 0f,
            embeddingModelInstalled = true,
            error = null,
            micGranted = false,
            onBack = {},
            onEnrollNameChange = {},
            onStartRecording = {},
            onCancel = {},
            onRequestDelete = {},
            onOpenSetup = {}
        )
    }

    @Test
    fun setupDownloading() = capture("setup_downloading") {
        SetupBody(state = SetupUiState(totalBytes = 99_000_000L, doneBytes = 41_000_000L, running = true), onStart = {})
    }

    @Test
    fun setupError() = capture("setup_error") {
        SetupBody(
            state = SetupUiState(totalBytes = 99_000_000L, doneBytes = 12_000_000L, error = "Download failed: timeout"),
            onStart = {}
        )
    }

    @Test
    fun engineCheck() = capture("engine_check") {
        EngineCheckBody(
            state = EngineCheckUiState(
                deviceInfo = DEVICE,
                results = mapOf(
                    "npu" to EngineCheckResult(
                        engine = "npu",
                        initMs = 0L,
                        firstRun = true,
                        audioMs = 0L,
                        processMs = 0L,
                        text = "",
                        error = "QNN device creation failed (14001)"
                    ),
                    "cpu" to EngineCheckResult(
                        engine = "cpu",
                        initMs = 412L,
                        firstRun = false,
                        audioMs = 3_800L,
                        processMs = 120L,
                        text = "after early nightfall the yellow lamps would light up here and there"
                    )
                )
            ),
            storageWarning = null,
            onBack = {},
            onRunCheck = {},
            onOpenSetup = {},
            onCopyReport = {},
            onShareReport = {}
        )
    }

    @Composable
    private fun session(
        status: SessionStatus = SessionStatus.DIARIZED,
        lines: List<TranscriptLine> = TRANSCRIPT,
        speakers: List<SpeakerEntity> = SPEAKERS,
        playing: Boolean = false
    ) {
        SessionBody(
            title = "Weekly sync",
            status = status,
            lines = lines,
            speakers = speakers,
            notFound = false,
            summaryState = SummaryUiState(),
            askState = AskUiState(messages = CHAT),
            playerState = PlayerState(
                ready = true,
                isPlaying = playing,
                positionMs = 9_000L,
                durationMs = 754_000L
            ),
            activeLineIndex = if (playing) 1 else null,
            following = true,
            exportState = ExportState.Idle,
            onShareAudio = {},
            onSaveAudio = {},
            onCancelExport = {},
            playback = PlaybackActions(),
            snackbarHostState = SnackbarHostState(),
            canContinueRecording = true,
            onContinueRecording = {},
            onBack = {},
            onShare = {},
            onRenameSpeaker = { _, _ -> },
            onRenameAndRemember = { _, _ -> },
            onRenameSession = {},
            effectiveSpeakerCount = 3,
            onReidentifySpeakers = {},
            onSummarise = {},
            onCancelSummary = {},
            onAsk = {},
            onRetryAsk = {},
            onCancelAsk = {},
            onClearQuestions = {},
            onOpenSettings = {}
        )
    }

    companion object {
        private const val PHONE = "w411dp-h891dp"

        /** Boox Note Air 6C: 1240 x 1860 px, reported as xhdpi (620 x 930 dp). */
        private const val BOOX = "w620dp-h930dp"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun variants(): List<Array<Any>> = listOf(
            Variant(name = "phone_light", size = PHONE, density = "xxhdpi"),
            Variant(name = "phone_dark", size = PHONE, density = "xxhdpi", dark = true),
            Variant(name = "phone_font200", size = PHONE, density = "xxhdpi", fontScale = 2f),
            Variant(name = "boox", size = BOOX, density = "xhdpi")
        ).map { arrayOf(it) }

        // 2025-03-14 09:30 UTC and a day later.
        private const val T0 = 1_741_944_600_000L
        private const val DAY = 86_400_000L

        private fun row(
            id: Long,
            title: String,
            createdAt: Long,
            durationMs: Long,
            status: SessionStatus,
            language: String = "en"
        ) = SessionRow(
            id = id,
            title = title,
            subtitle = sessionSubtitle(createdAt, durationMs, status, language, ZoneOffset.UTC),
            status = status
        )

        private val SESSIONS = listOf(
            row(5, "Recording #5", T0 + 3 * DAY, 0L, SessionStatus.RECORDING),
            row(4, "Weekly sync", T0 + 2 * DAY, 754_000L, SessionStatus.DIARIZING),
            row(3, "Réunion budget", T0 + DAY, 1_932_000L, SessionStatus.DIARIZED, language = "fr"),
            row(2, "Interview with Carol", T0, 3_725_000L, SessionStatus.DIARIZATION_FAILED),
            row(1, "Recording #1", T0 - DAY, 61_000L, SessionStatus.TRANSCRIBED)
        )

        private val LIVE_LINES = listOf(
            "So the first thing on the agenda is the release date.",
            "We said the end of the month, but QA needs another week.",
            "That's fine as long as the e-ink build is ready too."
        )

        private val SPEAKERS = listOf(
            SpeakerEntity(sessionId = 4, speakerIndex = 0, label = "Alice"),
            SpeakerEntity(sessionId = 4, speakerIndex = 1, label = "Speaker 2"),
            SpeakerEntity(sessionId = 4, speakerIndex = 2, label = "Bob")
        )

        private val TRANSCRIPT = listOf(
            TranscriptLine(
                id = 1,
                startMs = 0L,
                endMs = 4_000L,
                speaker = "Alice",
                text = "So the first thing on the agenda is the release date."
            ),
            TranscriptLine(
                id = 2,
                startMs = 4_500L,
                endMs = 9_800L,
                speaker = "Speaker 2",
                text = "We said the end of the month, but QA needs another week to go through the Boox build."
            ),
            TranscriptLine(
                id = 3,
                startMs = 10_200L,
                endMs = 13_000L,
                speaker = "Bob",
                text = "That's fine as long as the e-ink mode is in."
            ),
            TranscriptLine(
                id = 4,
                startMs = 13_400L,
                endMs = 18_000L,
                speaker = "Alice",
                text = "Agreed. Let's move on to the budget."
            ),
            TranscriptLine(
                id = 5,
                startMs = 18_600L,
                endMs = 25_000L,
                speaker = "Speaker 2",
                text = "I've put the numbers in the shared sheet; the short version is we're under " +
                    "by about ten percent."
            )
        )

        private val CHAT = listOf(
            ChatMessageEntity(
                id = 1,
                sessionId = 4,
                kind = MessageKind.QA,
                role = MessageRole.USER,
                content = "When is the release?",
                createdAt = 0L
            ),
            ChatMessageEntity(
                id = 2,
                sessionId = 4,
                kind = MessageKind.QA,
                role = MessageRole.ASSISTANT,
                content = "The release moves to the first week of next month, so QA has another " +
                    "week for the Boox build.",
                createdAt = 0L
            )
        )

        private val DEVICE = DeviceInfo(
            manufacturer = "ONYX",
            model = "NoteAir6C",
            socManufacturer = "QTI",
            socModel = "QCS6690",
            hardware = "qcom",
            androidVersion = "16 (API 36)",
            cdspLibraryPresent = true,
            htpSkelLibs = listOf("libQnnHtpV73Skel.so"),
            freeStorageBytes = 41_300_000_000L
        )

        private fun enrolled(name: String) = EnrolledSpeakerEntity(
            name = name,
            embedding = ByteArray(0),
            createdAt = 0L
        )
    }
}
