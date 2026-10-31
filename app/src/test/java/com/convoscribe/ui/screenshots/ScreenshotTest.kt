package com.convoscribe.ui.screenshots

import android.app.Application
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.convoscribe.PresetPickerDialog
import com.convoscribe.R
import com.convoscribe.audio.PlayerState
import com.convoscribe.backup.BackupFailure
import com.convoscribe.backup.BackupKind
import com.convoscribe.backup.BackupState
import com.convoscribe.data.ActionItemEntity
import com.convoscribe.data.ChatMessageEntity
import com.convoscribe.data.EnrolledSpeakerEntity
import com.convoscribe.data.Highlighted
import com.convoscribe.data.MessageKind
import com.convoscribe.data.MessageRole
import com.convoscribe.data.OpenActionItem
import com.convoscribe.data.Preset
import com.convoscribe.data.SearchLine
import com.convoscribe.data.SearchOptions
import com.convoscribe.data.SearchResult
import com.convoscribe.data.SearchText
import com.convoscribe.data.SessionEntity
import com.convoscribe.data.SessionStatus
import com.convoscribe.data.SpeakerEntity
import com.convoscribe.data.Tag
import com.convoscribe.data.TagUsage
import com.convoscribe.data.TranscriptLine
import com.convoscribe.data.TranscriptMark
import com.convoscribe.enginecheck.CleanupCheckResult
import com.convoscribe.enginecheck.DeviceInfo
import com.convoscribe.enginecheck.EngineCheckResult
import com.convoscribe.enginecheck.LiveCleanupVerdict
import com.convoscribe.enginecheck.SemanticCheckResult
import com.convoscribe.llm.Citation
import com.convoscribe.llm.MarkNotesProgress
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.llm.embedding.IndexProgress
import com.convoscribe.overlay.FloatingControlContent
import com.convoscribe.overlay.FloatingControlUi
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.ImportState
import com.convoscribe.recording.ImportStep
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.settings.FloatingShape
import com.convoscribe.sync.SyncStatus
import com.convoscribe.ui.actions.ActionsBody
import com.convoscribe.ui.actions.ActionsGrouping
import com.convoscribe.ui.actions.ActionsUiState
import com.convoscribe.ui.actions.ActionsViewModel
import com.convoscribe.ui.ask.AskAcrossBody
import com.convoscribe.ui.ask.AskAcrossMessage
import com.convoscribe.ui.ask.AskAcrossUiState
import com.convoscribe.ui.components.MarkNoteDialog
import com.convoscribe.ui.components.TagPickerDialog
import com.convoscribe.ui.enginecheck.EngineCheckBody
import com.convoscribe.ui.enginecheck.EngineCheckUiState
import com.convoscribe.ui.home.HomeBody
import com.convoscribe.ui.home.SessionRow
import com.convoscribe.ui.home.TagFilter
import com.convoscribe.ui.organise.PresetDraft
import com.convoscribe.ui.organise.PresetsBody
import com.convoscribe.ui.organise.TagsBody
import com.convoscribe.ui.record.CleanupOption
import com.convoscribe.ui.record.RecordBody
import com.convoscribe.ui.search.DateRange
import com.convoscribe.ui.search.FilterState
import com.convoscribe.ui.search.SearchBody
import com.convoscribe.ui.search.SearchResults
import com.convoscribe.ui.session.ActionItemActions
import com.convoscribe.ui.session.AskUiState
import com.convoscribe.ui.session.CleanupUi
import com.convoscribe.ui.session.ExportState
import com.convoscribe.ui.session.LineDialog
import com.convoscribe.ui.session.LineEditActions
import com.convoscribe.ui.session.PlaybackActions
import com.convoscribe.ui.session.SessionBody
import com.convoscribe.ui.session.SummaryUiState
import com.convoscribe.ui.session.TagsUi
import com.convoscribe.ui.settings.DataSection
import com.convoscribe.ui.settings.RestoreConfirmDialog
import com.convoscribe.ui.settings.SemanticSearchSection
import com.convoscribe.ui.settings.SettingsActions
import com.convoscribe.ui.settings.SettingsBody
import com.convoscribe.ui.settings.SettingsForm
import com.convoscribe.ui.settings.SettingsUiState
import com.convoscribe.ui.settings.SyncSection
import com.convoscribe.ui.setup.SetupBody
import com.convoscribe.ui.setup.SetupUiState
import com.convoscribe.ui.speakers.EnrollmentPhase
import com.convoscribe.ui.speakers.SpeakersBody
import com.convoscribe.ui.templates.SummaryTemplatesBody
import com.convoscribe.ui.templates.TemplateDraft
import com.convoscribe.ui.theme.ConvoScribeTheme
import com.convoscribe.update.Release
import com.convoscribe.update.ReleaseAsset
import com.convoscribe.update.SemVer
import com.convoscribe.update.UpdateFailure
import com.convoscribe.update.UpdateOffer
import com.convoscribe.update.UpdateState
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Assume
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
        val fontScale: Float = 1f,
        val eink: Boolean = false
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
            ConvoScribeTheme(darkTheme = variant.dark, eink = variant.eink) {
                content()
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${variant.name}/$screen.png")
    }

    /** Like [capture], but the whole screen, so dialogs and menus (own windows) are in the shot. */
    @OptIn(ExperimentalRoborazziApi::class)
    private fun captureScreen(screen: String, content: @Composable () -> Unit) {
        compose.setContent {
            ConvoScribeTheme(darkTheme = variant.dark, eink = variant.eink) {
                content()
            }
        }
        compose.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/${variant.name}/$screen.png")
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

    /** Two-way sync (issue #69): a recording still downloading, and a session removed from this device. */
    @Test
    fun homeSync() = capture("home_sync") {
        HomeBody(
            sessions = SYNC_SESSIONS,
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeImporting() = capture("home_importing") {
        HomeBody(
            sessions = SESSIONS,
            importing = ImportState.Running("Voice note 2026-10-18.m4a", ImportStep.TRANSCRIBING, 0.42f),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeUpdateAvailable() = capture("home_update_available") {
        HomeBody(
            sessions = SESSIONS,
            update = UpdateState.Available(UPDATE),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeUpdateDownloading() = capture("home_update_downloading") {
        HomeBody(
            sessions = SESSIONS,
            update = UpdateState.Downloading(UPDATE, 38_000_000L, 89_400_330L),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeUpdateSignatureMismatch() = capture("home_update_signature_mismatch") {
        HomeBody(
            sessions = SESSIONS,
            update = UpdateState.Failed(UPDATE, UpdateFailure.SIGNATURE_MISMATCH),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeTags() = capture("home_tags") {
        HomeBody(
            sessions = TAGGED_SESSIONS,
            tagFilter = TagFilter(tags = TAGS),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeTagFiltered() = capture("home_tag_filtered") {
        HomeBody(
            sessions = TAGGED_SESSIONS.filter { "Acme" in it.tags },
            tagFilter = TagFilter(tags = TAGS, selected = setOf(1L)),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun homeTagFilterEmpty() = capture("home_tag_filter_empty") {
        HomeBody(
            sessions = emptyList(),
            tagFilter = TagFilter(tags = TAGS, selected = setOf(3L)),
            onNewRecording = {},
            onOpenSession = {},
            onDelete = {},
            onSettings = {},
            onEngineCheck = {}
        )
    }

    @Test
    fun tagPicker() {
        assumeTextFieldDialogsRender()
        captureScreen("tag_picker") {
            TagPickerDialog(
                allTags = TAGS.map { it.name },
                selected = listOf("Acme"),
                onConfirm = {},
                onDismiss = {}
            )
        }
    }

    @Test
    fun tagsScreen() = capture("tags") {
        TagsBody(tags = TAG_USAGE, onBack = {}, onRename = { _, _ -> }, onDelete = {})
    }

    @Test
    fun tagsScreenEmpty() = capture("tags_empty") {
        TagsBody(tags = emptyList(), onBack = {}, onRename = { _, _ -> }, onDelete = {})
    }

    @Test
    fun presets() = capture("presets") {
        PresetsBody(
            presets = PRESETS,
            enrolledNames = listOf("Alice", "Bob"),
            summaryTemplates = SummaryTemplates.BUILT_IN,
            allTags = TAGS.map { it.name },
            cleanup = null,
            onBack = {},
            newDraft = { PresetDraft(options = ConversationOptions(language = "en")) },
            draftOf = { PresetDraft(it.id, it.name, ConversationOptions(language = it.language)) },
            onSave = {},
            onDelete = {}
        )
    }

    @Test
    fun presetEditor() = capture("preset_editor") {
        PresetsBody(
            presets = PRESETS,
            enrolledNames = listOf("Alice", "Bob"),
            summaryTemplates = SummaryTemplates.BUILT_IN,
            allTags = TAGS.map { it.name },
            cleanup = null,
            onBack = {},
            newDraft = { PresetDraft(options = ConversationOptions(language = "en")) },
            draftOf = { PresetDraft(it.id, it.name, ConversationOptions(language = it.language)) },
            onSave = {},
            onDelete = {},
            initialDraft = PresetDraft(
                id = 1,
                name = "Acme weekly",
                options = ConversationOptions(
                    language = "en",
                    numSpeakers = 4,
                    participants = listOf("Alice", "Bob"),
                    title = "Acme weekly {date}",
                    tags = listOf("Acme", "Weekly")
                )
            )
        )
    }

    @Test
    fun recordIdlePreset() = capture("record_idle_preset") {
        RecordBody(
            phase = RecordingPhase.IDLE,
            preparingNpu = false,
            elapsedMs = 0L,
            finalLines = emptyList(),
            partialText = "",
            error = null,
            micDeniedMessage = null,
            options = ConversationOptions(language = "en", title = "Acme weekly {date}", tags = listOf("Acme")),
            presets = PRESETS,
            activePreset = PRESETS.first(),
            onBack = {},
            onStart = {},
            onStop = {}
        )
    }

    @Test
    fun presetPicker() = captureScreen("preset_picker") {
        PresetPickerDialog(presets = PRESETS, onPick = {}, onDismiss = {})
    }

    @Test
    fun sessionTags() = capture("session_tags") {
        session(tags = listOf("Acme", "Weekly"))
    }

    @Test
    fun recordIdleTags() = capture("record_idle_tags") {
        RecordBody(
            phase = RecordingPhase.IDLE,
            preparingNpu = false,
            elapsedMs = 0L,
            finalLines = emptyList(),
            partialText = "",
            error = null,
            micDeniedMessage = null,
            options = ConversationOptions(
                language = "en",
                title = "Weekly sync {date}",
                tags = listOf("Acme", "Weekly")
            ),
            onBack = {},
            onStart = {},
            onStop = {}
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
    fun search() = capture("search") {
        SearchBody(
            query = "budget réunion",
            filters = FilterState(dateRange = DateRange.MONTH),
            options = SearchOptions(languages = listOf("en", "fr"), speakers = listOf("Alice", "Élodie")),
            results = SearchResults(query = "budget réunion", items = SEARCH_RESULTS),
            onQueryChange = {},
            onSubmit = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onOpen = { _, _, _ -> },
            onBack = {},
            autoFocus = false
        )
    }

    /** Search by meaning (issue #63): a word match with a passage found by meaning, then a meaning-only session. */
    @Test
    fun searchByMeaning() = capture("search_by_meaning") {
        SearchBody(
            query = "budget pour les vacances",
            filters = FilterState(),
            options = SearchOptions(languages = listOf("en", "fr"), speakers = listOf("Alice", "Élodie")),
            results = SearchResults(query = "budget pour les vacances", items = SEARCH_BY_MEANING_RESULTS),
            onQueryChange = {},
            onSubmit = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onOpen = { _, _, _ -> },
            onBack = {},
            autoFocus = false
        )
    }

    @Test
    fun askAcross() = capture("ask_across") {
        AskAcrossBody(
            state = AskAcrossUiState(
                messages = ASK_ACROSS_CHAT,
                filters = FilterState(speaker = "Élodie"),
                options = SearchOptions(languages = listOf("en", "fr"), speakers = listOf("Alice", "Élodie")),
                // Session 3 has been deleted since it was cited.
                sessionIds = setOf(1L, 2L)
            ),
            onAsk = {},
            onRetry = {},
            onCancel = {},
            onClearHistory = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onTag = {},
            onOpenCitation = {},
            onOpenSettings = {},
            onBack = {}
        )
    }

    /** Semantic search (issue #61): an answer matched by meaning, and sessions still waiting to be indexed. */
    @Test
    fun askAcrossSemantic() = capture("ask_across_semantic") {
        AskAcrossBody(
            state = AskAcrossUiState(
                messages = ASK_ACROSS_CHAT.map {
                    if (it.role == MessageRole.ASSISTANT) {
                        it.copy(
                            semantic = true
                        )
                    } else {
                        it
                    }
                },
                options = SearchOptions(languages = listOf("en", "fr"), speakers = listOf("Alice", "Élodie")),
                sessionIds = setOf(1L, 2L, 3L),
                unindexed = 3
            ),
            onAsk = {},
            onRetry = {},
            onCancel = {},
            onClearHistory = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onTag = {},
            onOpenCitation = {},
            onOpenSettings = {},
            onBack = {}
        )
    }

    /** The Settings section for the semantic search model (issue #61): not installed, then installed. */
    @Test
    fun settingsSemantic() = capture("settings_semantic") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SemanticSearchSection(installed = false, downloading = false, progress = IndexProgress(0, 0), onDownload = {
            }, onDelete = {})
            SemanticSearchSection(
                installed = true,
                downloading = false,
                progress = IndexProgress(
                    12,
                    40
                ),
                onDownload = {
                },
                onDelete = {}
            )
        }
    }

    /** Indexing any time (issue #65), with *Index now* while sessions wait. */
    @Test
    fun settingsSemanticAnyTime() = capture("settings_semantic_any_time") {
        SemanticSearchSection(
            installed = true,
            downloading = false,
            progress = IndexProgress(38, 40),
            onDownload = {},
            onDelete = {},
            indexMode = "anytime"
        )
    }

    /** Settings → Sync (issue #68): off, with no server yet. */
    @Test
    fun settingsSyncOff() = capture("settings_sync_off") {
        SyncSection(
            url = "",
            key = "",
            onUrlChange = {},
            onKeyChange = {},
            configured = false,
            enabled = false,
            status = SyncStatus(),
            onEnabledChange = {},
            onSyncNow = {}
        )
    }

    /** Sync on, with sessions waiting after a failed run (issue #68). */
    @Test
    fun settingsSync() = capture("settings_sync") {
        SyncSection(
            url = "https://sync.example.com",
            key = "secret",
            onUrlChange = {},
            onKeyChange = {},
            configured = true,
            enabled = true,
            status = SyncStatus(
                lastSyncedAt = 1_761_700_000_000L,
                waiting = 3,
                lastError = "Network error calling https://sync.example.com/api/sync/v1/blobs: timeout"
            ),
            onEnabledChange = {},
            onSyncNow = {}
        )
    }

    /** Two-way sync (issue #69): recordings waiting for Wi-Fi, mobile data off. */
    @Test
    fun settingsSyncDownloading() = capture("settings_sync_downloading") {
        SyncSection(
            url = "https://sync.example.com",
            key = "secret",
            onUrlChange = {},
            onKeyChange = {},
            configured = true,
            enabled = true,
            status = SyncStatus(lastSyncedAt = 1_761_700_000_000L, downloading = 2),
            onEnabledChange = {},
            onSyncNow = {},
            audioOnMobileData = false
        )
    }

    /** Sync running after *Sync now*, served over plain http:// (issue #68). */
    @Test
    fun settingsSyncRunning() = capture("settings_sync_running") {
        SyncSection(
            url = "http://sync.home.lan:3000",
            key = "secret",
            onUrlChange = {},
            onKeyChange = {},
            configured = true,
            enabled = true,
            status = SyncStatus(running = true),
            onEnabledChange = {},
            onSyncNow = {}
        )
    }

    /** Settings → Data (issue #25): nothing running, then a backup halfway. */
    @Test
    fun settingsData() = capture("settings_data") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DataSection(state = BackupState.Idle, onBackup = {}, onRestore = {}, onCancel = {}, onDismiss = {})
            DataSection(
                state = BackupState.Running(BackupKind.BACKUP, 0.42f),
                onBackup = {},
                onRestore = {},
                onCancel = {},
                onDismiss = {}
            )
        }
    }

    /** A restore that finished, and one refused as made by a newer version (issue #25). */
    @Test
    fun settingsDataResults() = capture("settings_data_results") {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            DataSection(
                state = BackupState.Done(BackupKind.RESTORE, sessions = 12, skipped = 3),
                onBackup = {},
                onRestore = {},
                onCancel = {},
                onDismiss = {}
            )
            DataSection(
                state = BackupState.Failed(BackupKind.RESTORE, BackupFailure.TOO_NEW),
                onBackup = {},
                onRestore = {},
                onCancel = {},
                onDismiss = {}
            )
        }
    }

    /** Asking before a restore, with *Also restore settings* ticked (issue #25). */
    @Test
    fun restoreConfirm() = captureScreen("restore_confirm") {
        RestoreConfirmDialog(onConfirm = {}, onDismiss = {})
    }

    /** Engine check's semantic search card after a check (issue #61). */
    @Test
    fun engineCheckSemantic() = capture("engine_check_semantic") {
        EngineCheckBody(
            state = EngineCheckUiState(
                deviceInfo = DEVICE,
                semanticAvailable = true,
                semantic = SemanticCheckResult(
                    loadMs = 2_140L,
                    passageMs = 310L,
                    queryMs = 95L,
                    passages = 8,
                    memoryBeforeMb = 180L,
                    peakMemoryMb = 412L
                )
            ),
            storageLow = false,
            onBack = {},
            onRunCheck = {},
            onOpenSetup = {},
            onCopyReport = {},
            onShareReport = {}
        )
    }

    @Test
    fun askAcrossEmpty() = capture("ask_across_empty") {
        AskAcrossBody(
            state = AskAcrossUiState(options = SearchOptions(languages = listOf("en", "fr")), sessionIds = emptySet()),
            onAsk = {},
            onRetry = {},
            onCancel = {},
            onClearHistory = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onTag = {},
            onOpenCitation = {},
            onOpenSettings = {},
            onBack = {}
        )
    }

    @Test
    fun searchNoResults() = capture("search_no_results") {
        SearchBody(
            query = "quarterly",
            filters = FilterState(language = "fr"),
            options = SearchOptions(languages = listOf("en", "fr")),
            results = SearchResults(query = "quarterly"),
            onQueryChange = {},
            onSubmit = {},
            onLanguage = {},
            onDateRange = {},
            onSpeaker = {},
            onOpen = { _, _, _ -> },
            onBack = {},
            autoFocus = false
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
    fun recordIdleWithCleanup() = capture("record_idle_cleanup") {
        RecordBody(
            phase = RecordingPhase.IDLE,
            preparingNpu = false,
            elapsedMs = 0L,
            finalLines = emptyList(),
            partialText = "",
            error = null,
            micDeniedMessage = null,
            options = ConversationOptions(language = "en", cleanAudio = true),
            cleanup = CleanupOption(liveNotRecommended = true),
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
            inputLevel = 0.55f,
            onBack = {},
            onStart = {},
            onStop = {}
        )
    }

    @Test
    fun recordPaused() = capture("record_paused") {
        RecordBody(
            phase = RecordingPhase.PAUSED,
            preparingNpu = false,
            elapsedMs = 754_000L,
            finalLines = LIVE_LINES,
            partialText = "",
            error = null,
            micDeniedMessage = null,
            onBack = {},
            onStart = {},
            onStop = {}
        )
    }

    @Test
    fun floatingControl() = capture("floating_control") {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            for (paused in listOf(false, true)) {
                FloatingControlContent(
                    shape = FloatingShape.STANDARD,
                    ui = FloatingControlUi(paused = paused, elapsedMs = 754_000L),
                    onOpen = {},
                    onMark = {},
                    onPauseResume = {},
                    onStop = {},
                    onShapeChange = {}
                )
            }
            for (paused in listOf(false, true)) {
                FloatingControlContent(
                    shape = FloatingShape.MINIMAL,
                    ui = FloatingControlUi(paused = paused),
                    onOpen = {},
                    onMark = {},
                    onPauseResume = {},
                    onStop = {},
                    onShapeChange = {}
                )
            }
            for (transcript in listOf(
                FloatingControlUi.transcriptTail(
                    LIVE_LINES,
                    "",
                    FloatingControlUi.maxCharsFor(LocalDensity.current.fontScale)
                ),
                null
            )) {
                FloatingControlContent(
                    shape = FloatingShape.EXPANDED,
                    ui = FloatingControlUi(paused = false, elapsedMs = 754_000L, transcript = transcript),
                    onOpen = {},
                    onMark = {},
                    onPauseResume = {},
                    onStop = {},
                    onShapeChange = {}
                )
            }
        }
    }

    @Test
    fun sessionTranscript() = capture("session_transcript") {
        session(playing = true)
    }

    /** A session from another device whose recording is still downloading (issue #69). */
    @Test
    fun sessionAudioDownloading() = capture("session_audio_downloading") {
        session(audioDownloading = true)
    }

    @Test
    fun sessionCleaned() = capture("session_cleaned") {
        session(cleanup = CleanupUi(cleaned = true, available = true, enabled = true, playCleaned = true))
    }

    @Test
    fun sessionLineMenu() = captureScreen("session_line_menu") {
        session(lineMenu = 2L)
    }

    @Test
    fun sessionEditLine() {
        assumeTextFieldDialogsRender()
        captureScreen("session_edit_line") { session(lineDialog = LineDialog.EditText(2L)) }
    }

    @Test
    fun sessionChangeSpeaker() = captureScreen("session_change_speaker") {
        session(lineDialog = LineDialog.ChangeSpeaker(2L))
    }

    @Test
    fun sessionSplitLine() {
        assumeTextFieldDialogsRender()
        captureScreen("session_split_line") { session(lineDialog = LineDialog.Split(2L)) }
    }

    /**
     * A dialog holding a text field never lets Compose go idle under
     * Robolectric when animations run (the existing rename dialog does
     * the same), so those dialogs are only shot on the e-ink variant,
     * where animations are off.
     */
    private fun assumeTextFieldDialogsRender() = Assume.assumeTrue(variant.eink)

    @Test
    fun sessionMarks() = capture("session_marks") {
        session(playing = true, marks = MARKS)
    }

    @Test
    fun sessionMarksList() = captureScreen("session_marks_list") {
        session(marks = MARKS, marksOpen = true)
    }

    @Test
    fun recordMarkNote() {
        assumeTextFieldDialogsRender()
        captureScreen("record_mark_note") {
            MarkNoteDialog(time = "12:34", initialNote = "Release date moves", onConfirm = {}, onDismiss = {})
        }
    }

    @Test
    fun sessionDiarizing() = capture("session_diarizing") {
        session(
            status = SessionStatus.DIARIZING,
            speakers = emptyList(),
            lines = TRANSCRIPT.map { it.copy(speaker = null, speakerIndex = null) }
        )
    }

    @Test
    fun sessionSpeakersPending() = capture("session_speakers_pending") {
        session(
            status = SessionStatus.DIARIZATION_PENDING,
            speakers = emptyList(),
            lines = TRANSCRIPT.map { it.copy(speaker = null, speakerIndex = null) },
            waitingForCharger = true
        )
    }

    @Test
    fun sessionEmpty() = capture("session_empty") {
        session(lines = emptyList(), speakers = emptyList())
    }

    @Test
    fun sessionSummary() = capture("session_summary") {
        session(tab = 1, summary = SummaryUiState(saved = SUMMARY, savedTemplateName = "Meeting"))
    }

    @Test
    fun sessionSummaryNotes() = capture("session_summary_notes") {
        session(tab = 1, marks = NOTED_MARKS, summary = SummaryUiState(notes = MarkNotesProgress(done = 2, total = 3)))
    }

    /** Issue #31: the summary's action items as a checklist, one ticked off. */
    @Test
    fun sessionSummaryActions() = capture("session_summary_actions") {
        val brief = "- Release moves a week.\n- QA tests the Boox build for another week."
        session(tab = 1, summary = SummaryUiState(saved = brief, savedTemplateName = "Meeting", items = ACTION_ITEMS))
    }

    @Test
    fun openActions() = capture("open_actions") {
        ActionsBody(
            state = ActionsUiState(
                groups = ActionsViewModel.group(OPEN_ACTIONS, ActionsGrouping.SESSION),
                loaded = true
            ),
            onBack = {},
            onGrouping = {},
            actions = ActionItemActions(),
            onShare = {},
            onAddToCalendar = {},
            onOpen = {}
        )
    }

    @Test
    fun openActionsByOwner() = capture("open_actions_by_owner") {
        ActionsBody(
            state = ActionsUiState(
                grouping = ActionsGrouping.OWNER,
                groups = ActionsViewModel.group(OPEN_ACTIONS, ActionsGrouping.OWNER),
                loaded = true
            ),
            onBack = {},
            onGrouping = {},
            actions = ActionItemActions(),
            onShare = {},
            onAddToCalendar = {},
            onOpen = {}
        )
    }

    @Test
    fun openActionsEmpty() = capture("open_actions_empty") {
        ActionsBody(
            state = ActionsUiState(loaded = true),
            onBack = {},
            onGrouping = {},
            actions = ActionItemActions(),
            onShare = {},
            onAddToCalendar = {},
            onOpen = {}
        )
    }

    @Test
    fun actionItemEditor() {
        assumeTextFieldDialogsRender()
        captureScreen("action_item_editor") {
            ActionsBody(
                state = ActionsUiState(
                    groups = ActionsViewModel.group(OPEN_ACTIONS, ActionsGrouping.SESSION),
                    loaded = true
                ),
                onBack = {},
                onGrouping = {},
                actions = ActionItemActions(),
                onShare = {},
                onAddToCalendar = {},
                onOpen = {},
                initialEditing = OPEN_ACTIONS.first().item
            )
        }
    }

    @Test
    fun sessionMarksListNotes() = captureScreen("session_marks_list_notes") {
        session(marks = NOTED_MARKS, marksOpen = true)
    }

    @Test
    fun sessionAsk() = capture("session_ask") {
        session(tab = 2)
    }

    @Test
    fun sessionAskWaiting() = capture("session_ask_waiting") {
        val question = CHAT.first().copy(id = 3, content = "Who is shipping the e-ink build?")
        session(tab = 2, ask = AskUiState(messages = CHAT + question, running = true))
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
            actions = SettingsActions(),
            installedVersion = "0.2.1",
            installedVersionCode = 3L,
            update = UpdateState.ReadyToInstall(UPDATE, java.io.File("convoscribe-0.3.0.apk"))
        )
    }

    @Test
    fun summaryTemplates() = capture("summary_templates") {
        SummaryTemplatesBody(
            templates = SummaryTemplates.BUILT_IN + USER_TEMPLATE,
            onBack = {},
            onSave = {},
            onDuplicate = {},
            onDelete = {}
        )
    }

    @Test
    fun summaryTemplateEditor() {
        assumeTextFieldDialogsRender()
        captureScreen("summary_template_editor") {
            SummaryTemplatesBody(
                templates = SummaryTemplates.BUILT_IN + USER_TEMPLATE,
                onBack = {},
                onSave = {},
                onDuplicate = {},
                onDelete = {},
                initialDraft = TemplateDraft(USER_TEMPLATE.key, USER_TEMPLATE.name, USER_TEMPLATE.body)
            )
        }
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
            storageLow = false,
            onBack = {},
            onRunCheck = {},
            onOpenSetup = {},
            onCopyReport = {},
            onShareReport = {}
        )
    }

    @Test
    fun engineCheckCleanup() = capture("engine_check_cleanup") {
        EngineCheckBody(
            state = EngineCheckUiState(
                deviceInfo = DEVICE,
                results = mapOf(
                    "cpu" to EngineCheckResult(
                        engine = "cpu",
                        initMs = 412L,
                        firstRun = false,
                        audioMs = 3_800L,
                        processMs = 1_900L,
                        text = "after early nightfall the yellow lamps would light up here and there"
                    )
                ),
                cleanupAvailable = true,
                cleanup = CleanupCheckResult(audioMs = 3_800L, processMs = 760L),
                cleanupEngine = "cpu",
                liveVerdict = LiveCleanupVerdict.MARGINAL
            ),
            storageLow = false,
            onBack = {},
            onRunCheck = {},
            onOpenSetup = {},
            onCopyReport = {},
            onShareReport = {}
        )
    }

    /** The adaptive icon's foreground on its background, in the launcher's circle mask. */
    @Test
    fun launcherIcon() = capture("launcher_icon") {
        Box(
            modifier = Modifier
                .size(192.dp)
                .clip(CircleShape)
                .background(colorResource(R.color.ic_launcher_background))
        ) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    @Composable
    private fun session(
        status: SessionStatus = SessionStatus.DIARIZED,
        lines: List<TranscriptLine> = TRANSCRIPT,
        speakers: List<SpeakerEntity> = SPEAKERS,
        playing: Boolean = false,
        tab: Int = 0,
        summary: SummaryUiState = SummaryUiState(),
        waitingForCharger: Boolean = false,
        lineMenu: Long? = null,
        lineDialog: LineDialog? = null,
        marks: List<TranscriptMark> = emptyList(),
        marksOpen: Boolean = false,
        cleanup: CleanupUi = CleanupUi(),
        tags: List<String> = emptyList(),
        ask: AskUiState = AskUiState(messages = CHAT),
        audioDownloading: Boolean = false
    ) {
        SessionBody(
            createdAt = T0 + 2 * DAY,
            durationMs = 754_000L,
            title = "Weekly sync",
            status = status,
            lines = lines,
            speakers = speakers,
            notFound = false,
            summaryState = summary,
            initialTab = tab,
            askState = ask,
            playerState = PlayerState(
                ready = !audioDownloading,
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
            audioDownloading = audioDownloading,
            onSummarise = {},
            onCancelSummary = {},
            onAsk = {},
            onRetryAsk = {},
            onCancelAsk = {},
            onClearQuestions = {},
            onOpenSettings = {},
            waitingForCharger = waitingForCharger,
            onCancelIdentify = {},
            lineEdits = LineEditActions(),
            initialLineMenu = lineMenu,
            initialLineDialog = lineDialog,
            marks = marks,
            initialMarksOpen = marksOpen,
            cleanup = cleanup,
            tagging = TagsUi(tags = tags, allTags = TAGS.map { it.name })
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
            // Auto display mode picks e-ink on a Boox.
            Variant(name = "boox", size = BOOX, density = "xhdpi", eink = true)
        ).map { arrayOf(it) }

        // 2025-03-14 09:30 UTC and a day later.
        private const val T0 = 1_741_944_600_000L
        private const val DAY = 86_400_000L

        private val SEARCH_TERMS = SearchText.terms("budget réunion")

        private fun hit(id: Long, startMs: Long, speaker: String?, index: Int?, text: String) = SearchLine(
            segmentId = id,
            startMs = startMs,
            speaker = speaker,
            speakerIndex = index,
            text = Highlighted(
                text,
                SearchText.matchRanges(text, SearchText.terms("budget")) +
                    SearchText.matchRanges(text, SearchText.terms("réunion"))
            )
        )

        private val SEARCH_RESULTS = listOf(
            SearchResult(
                session = SessionEntity(
                    id = 1,
                    title = "Réunion budget Q2",
                    createdAt = T0 + DAY,
                    language = "fr",
                    status = SessionStatus.DIARIZED
                ),
                title = SearchText.match("Réunion budget Q2", SEARCH_TERMS)!!,
                speakers = emptyList(),
                summary = null,
                lines = listOf(
                    hit(11, 64_000, "Élodie", 0, "On commence la réunion par le budget marketing."),
                    hit(12, 312_000, "Alice", 1, "Le budget de la réunion de lundi est validé."),
                    hit(13, 845_000, "Élodie", 0, "Pour la prochaine réunion, revoir le budget."),
                    hit(14, 1_210_000, null, null, "Budget, réunion, et planning.")
                )
            ),
            SearchResult(
                session = SessionEntity(
                    id = 2,
                    title = "Planning call",
                    createdAt = T0,
                    language = "en",
                    status = SessionStatus.DIARIZED
                ),
                title = Highlighted("Planning call"),
                speakers = emptyList(),
                summary = SearchText.excerpt(
                    "The team agreed to move the réunion to Thursday and to cap the travel budget at 2,000 euros.",
                    SEARCH_TERMS
                ),
                lines = emptyList()
            )
        )

        private val MEANING_TERMS = SearchText.terms("budget pour les vacances")

        private val SEARCH_BY_MEANING_RESULTS = listOf(
            SearchResult(
                session = SessionEntity(
                    id = 1,
                    title = "Réunion budget Q2",
                    createdAt = T0 + DAY,
                    language = "fr",
                    status = SessionStatus.DIARIZED
                ),
                title = Highlighted("Réunion budget Q2"),
                speakers = emptyList(),
                summary = null,
                lines = listOf(
                    SearchLine(
                        segmentId = 11,
                        startMs = 64_000,
                        speaker = "Élodie",
                        speakerIndex = 0,
                        text = SearchText.match("Le budget pour les vacances de juillet est validé.", MEANING_TERMS)!!
                    ),
                    SearchLine(
                        segmentId = 15,
                        startMs = 402_000,
                        speaker = "Alice",
                        speakerIndex = 1,
                        text = Highlighted(
                            "On ne peut pas dépenser plus de deux mille euros pour le voyage, hôtel compris."
                        ),
                        byMeaning = true
                    )
                )
            ),
            SearchResult(
                session = SessionEntity(
                    id = 2,
                    title = "Trip planning",
                    createdAt = T0,
                    language = "en",
                    status = SessionStatus.DIARIZED
                ),
                title = Highlighted("Trip planning"),
                speakers = emptyList(),
                summary = Highlighted("We capped the holiday spending at 2,000 euros and booked the train."),
                summaryByMeaning = true,
                lines = listOf(
                    SearchLine(
                        segmentId = 21,
                        startMs = 95_000,
                        speaker = "Carol",
                        speakerIndex = 2,
                        text = Highlighted(
                            "How much money can we put into the summer trip? Flights first, then hotels."
                        ),
                        byMeaning = true
                    )
                )
            )
        )

        private fun row(
            id: Long,
            title: String,
            createdAt: Long,
            durationMs: Long,
            status: SessionStatus,
            language: String = "en",
            speakers: Int = 0,
            tags: List<String> = emptyList()
        ) = SessionRow(
            id = id,
            title = title,
            createdAt = createdAt,
            durationMs = durationMs,
            status = status,
            language = language,
            speakerCount = speakers,
            tags = tags
        )

        private val UPDATE = UpdateOffer(
            version = SemVer(0, 3, 0),
            release = Release(
                tag = "v0.3.0",
                name = "ConvoScribe 0.3.0",
                notes = "In-app updates.",
                htmlUrl = "https://github.com/edkief/convoscribe/releases/tag/v0.3.0",
                prerelease = false,
                draft = false,
                assets = emptyList()
            ),
            apk = ReleaseAsset("convoscribe-0.3.0.apk", "https://example.test/convoscribe-0.3.0.apk", 89_400_330L),
            sha256 = null
        )

        private val SESSIONS = listOf(
            row(5, "Recording #5", T0 + 3 * DAY, 0L, SessionStatus.RECORDING),
            row(4, "Weekly sync", T0 + 2 * DAY, 754_000L, SessionStatus.DIARIZING),
            row(3, "Réunion budget", T0 + DAY, 1_932_000L, SessionStatus.DIARIZED, language = "fr", speakers = 4),
            row(2, "Interview with Carol", T0, 3_725_000L, SessionStatus.DIARIZATION_FAILED),
            row(1, "Recording #1", T0 - DAY, 61_000L, SessionStatus.DIARIZED, speakers = 2)
        )

        private val SYNC_SESSIONS = listOf(
            row(3, "Weekly sync", T0 + 2 * DAY, 754_000L, SessionStatus.DIARIZED, speakers = 3)
                .copy(synced = true, audioDownloading = true, syncId = "a"),
            SessionRow(
                id = 0,
                title = "Interview with Carol",
                createdAt = T0 + DAY,
                durationMs = 3_725_000L,
                status = SessionStatus.TRANSCRIBED,
                syncId = "b",
                synced = true,
                onServerOnly = true
            ),
            row(1, "Recording #1", T0 - DAY, 61_000L, SessionStatus.DIARIZED, speakers = 2)
                .copy(synced = true, syncId = "c")
        )

        private val TAGS = listOf(Tag(1, "Acme"), Tag(2, "Weekly"), Tag(3, "Hiring"))

        private val PRESETS = listOf(
            Preset(
                id = 1,
                name = "Acme weekly",
                titlePattern = "Acme weekly {date}",
                language = "en",
                numSpeakers = 4,
                participants = listOf("Alice", "Bob"),
                tags = listOf("Acme", "Weekly"),
                summaryTemplate = SummaryTemplates.MEETING_KEY
            ),
            Preset(
                id = 2,
                name = "Entretien",
                language = "fr",
                tags = listOf("Hiring"),
                summaryTemplate = SummaryTemplates.INTERVIEW_KEY
            )
        )

        private val TAG_USAGE = listOf(TagUsage(1, "Acme", 12), TagUsage(2, "Weekly", 1), TagUsage(3, "Hiring", 0))

        private val TAGGED_SESSIONS = listOf(
            row(
                4,
                "Weekly sync",
                T0 + 2 * DAY,
                754_000L,
                SessionStatus.DIARIZED,
                speakers = 3,
                tags = listOf("Acme", "Weekly")
            ),
            row(
                3,
                "Réunion budget",
                T0 + DAY,
                1_932_000L,
                SessionStatus.DIARIZED,
                language = "fr",
                tags = listOf("Acme")
            ),
            row(2, "Interview with Carol", T0, 3_725_000L, SessionStatus.DIARIZED, tags = listOf("Hiring")),
            row(1, "Recording 2025-03-13 09:30", T0 - DAY, 61_000L, SessionStatus.DIARIZED)
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

        private val MARKS = listOf(
            TranscriptMark(id = 1, atMs = 6_000L, note = "Release date moves a week"),
            TranscriptMark(id = 2, atMs = 402_000L)
        )

        private val NOTED_MARKS = listOf(
            TranscriptMark(
                id = 1,
                atMs = 6_000L,
                note = "Release date moves a week",
                expandedNote = "Alice says the release moves from the end of the month to the week after, " +
                    "because QA needs another week. Bob is fine with it if the e-ink build is ready too."
            ),
            TranscriptMark(
                id = 2,
                atMs = 402_000L,
                expandedNote = "- Bob takes the e-ink build.\n- Alice checks the store listing by Friday.",
                expandedStale = true
            ),
            TranscriptMark(id = 3, atMs = 610_000L, note = "Pricing")
        )

        private val TRANSCRIPT = listOf(
            TranscriptLine(
                id = 1,
                startMs = 0L,
                endMs = 4_000L,
                speaker = "Alice",
                speakerIndex = 0,
                text = "So the first thing on the agenda is the release date."
            ),
            TranscriptLine(
                id = 2,
                startMs = 4_500L,
                endMs = 9_800L,
                speaker = "Speaker 2",
                speakerIndex = 1,
                text = "We said the end of the month, but QA needs another week to go through the Boox build."
            ),
            TranscriptLine(
                id = 3,
                startMs = 10_200L,
                endMs = 13_000L,
                speaker = "Bob",
                speakerIndex = 2,
                text = "That's fine as long as the e-ink mode is in.",
                edited = true
            ),
            TranscriptLine(
                id = 4,
                startMs = 13_400L,
                endMs = 18_000L,
                speaker = "Alice",
                speakerIndex = 0,
                text = "Agreed. Let's move on to the budget."
            ),
            TranscriptLine(
                id = 5,
                startMs = 18_600L,
                endMs = 25_000L,
                speaker = "Speaker 2",
                speakerIndex = 1,
                text = "I've put the numbers in the shared sheet; the short version is we're under " +
                    "by about ten percent."
            )
        )

        // Markdown, as the LLM writes it (issue #53).
        private val SUMMARY = "## Summary\n\nThe team moved the release to the **first week of next month** so " +
            "QA can test the Boox build for another week. The e-ink mode is in scope for the release.\n\n" +
            "## Decisions\n\n" +
            "- Release moves a week.\n" +
            "- Budget: spending is about *ten percent* under plan; the numbers are in the shared sheet.\n\n" +
            "## Action items\n\n" +
            "1. Bob — ship the e-ink build.\n" +
            "2. Alice — check the store listing by Friday."

        private fun actionItem(
            id: Long,
            text: String,
            owner: String? = null,
            due: String? = null,
            atMs: Long? = null,
            done: Boolean = false,
            sessionId: Long = 1
        ) = ActionItemEntity(
            id = id,
            sessionId = sessionId,
            text = text,
            extractedText = text,
            owner = owner,
            dueDate = due,
            atMs = atMs,
            done = done,
            position = id.toInt(),
            createdAt = T0
        )

        private val ACTION_ITEMS = listOf(
            actionItem(1, "Ship the e-ink build", owner = "Bob", atMs = 64_000L),
            actionItem(2, "Check the store listing", owner = "Alice", due = "2025-03-21", atMs = 128_000L),
            actionItem(3, "Share the budget sheet", owner = "Élodie", done = true)
        )

        private val OPEN_ACTIONS = listOf(
            OpenActionItem(ACTION_ITEMS[0], "Weekly sync", T0 + 2 * DAY),
            OpenActionItem(ACTION_ITEMS[1], "Weekly sync", T0 + 2 * DAY),
            OpenActionItem(
                actionItem(4, "Send the signed contract to legal", owner = "alice", due = "2025-03-18", sessionId = 2),
                "Acme kickoff",
                T0
            ),
            OpenActionItem(actionItem(5, "Book a room for the retro", sessionId = 2), "Acme kickoff", T0)
        )

        private val ASK_ACROSS_CHAT = listOf(
            AskAcrossMessage(1L, MessageRole.USER, "What did Élodie say about the budget?"),
            AskAcrossMessage(
                id = 2L,
                role = MessageRole.ASSISTANT,
                content = "Élodie said the Q2 budget is capped at **40k** (Réunion budget Q2, 2025-03-15, 04:12), " +
                    "and later that marketing could take a quarter of it (Weekly sync, 2025-03-07, 12:40).",
                citations = listOf(
                    Citation(1L, "Réunion budget Q2", "2025-03-15", segmentId = 11L, atMs = 252_000L),
                    Citation(3L, "Weekly sync", "2025-03-07", segmentId = 31L, atMs = 760_000L)
                ),
                excerptCount = 7,
                sessionCount = 4
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
                content = "The release moves to the **first week of next month**:\n\n" +
                    "- QA gets another week for the Boox build.\n- The e-ink mode ships with it.",
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

        private val USER_TEMPLATE = SummaryTemplate(
            key = SummaryTemplates.userKey(1),
            name = "Standup",
            body = "Three bullet lists: done yesterday, planned today, blockers. One line per person.",
            builtIn = false
        )

        private fun enrolled(name: String) = EnrolledSpeakerEntity(
            name = name,
            embedding = ByteArray(0),
            createdAt = 0L
        )
    }
}
