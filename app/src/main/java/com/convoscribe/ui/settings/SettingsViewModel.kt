package com.convoscribe.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.TAG
import com.convoscribe.asr.server.ServerAsrException
import com.convoscribe.data.SummaryTemplateStore
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.SummaryTemplate
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.llm.embedding.IndexProgress
import com.convoscribe.llm.embedding.SemanticIndexRunner
import com.convoscribe.llm.toUserMessage
import com.convoscribe.models.ModelCatalog
import com.convoscribe.models.ModelStore
import com.convoscribe.settings.AppSettings
import com.convoscribe.settings.DEFAULT_DIARIZATION_EMBEDDING
import com.convoscribe.settings.DEFAULT_DIARIZATION_STEP_SECONDS
import com.convoscribe.settings.DisplayMode
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.settings.SpeakerIdMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editable form state captured by the Settings screen. The screen
 * hands each `OutlinedTextField` a string so the user can edit
 * even invalid intermediate values (for example an empty "Max
 * transcript characters"); the ViewModel rejects the bad value
 * on [SettingsViewModel.save] by falling back to the previous
 * `AppSettings.maxTranscriptChars` when the parsed int is `null`.
 *
 * The numeric sliders and segmented buttons populate `numThreads`,
 * `numSpeakers` and `clusterThreshold` directly because their
 * controls only produce valid values.
 *
 * `baseUrl` and `apiKey` are strings even though the repository
 * persists them as String; an empty base URL is the documented
 * "LLM not configured" sentinel.
 */
data class SettingsForm(
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val maxChars: String = "24000",
    val numThreads: Int = 4,
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
    val speakerMatchThreshold: Float = 0.5f,
    /** Transcription server (issue #58): base URL, API key and model, saved by Save like the LLM's. */
    val serverUrl: String = "",
    val serverKey: String = "",
    val serverModel: String = ""
) {
    /**
     * Build a [SettingsForm] from a persisted [AppSettings]. The
     * API key lives in [com.convoscribe.settings.SecretStore], not
     * on the [AppSettings] object, so it is read separately and
     * pre-filled here for editing.
     */
    companion object {
        fun from(settings: AppSettings, apiKey: String, serverKey: String = ""): SettingsForm = SettingsForm(
            baseUrl = settings.llmBaseUrl,
            apiKey = apiKey,
            serverUrl = settings.asrServerUrl,
            serverKey = serverKey,
            serverModel = settings.asrServerModel,
            model = settings.llmModel,
            maxChars = settings.maxTranscriptChars.toString(),
            numThreads = settings.numThreads,
            numSpeakers = settings.numSpeakers,
            clusterThreshold = settings.clusterThreshold,
            speakerMatchThreshold = settings.speakerMatchThreshold
        )
    }
}

/**
 * Outcome of "Test connection". The screen words it; [Failed.message]
 * is the client's own error text (see [com.convoscribe.llm.toUserMessage]).
 */
sealed interface ConnectionTestResult {
    /** [count] models were listed; [models] holds the first few ids. */
    data class Connected(val count: Int, val models: List<String>) : ConnectionTestResult

    data class Failed(val message: String) : ConnectionTestResult
}

/**
 * Snapshot rendered by [SettingsScreen]. The form is mutable
 * (every keystroke produces a new one); the rest are status
 * fields the screen consumes verbatim.
 *
 * @property form in-flight edits the user has typed; the source
 *   of truth for `Save` and `Test connection`.
 * @property asrEngine `"npu"` or `"cpu"` — kept separate from
 *   [SettingsForm] because the segmented button writes
 *   immediately on click (no "Save" round-trip).
 * @property asrLanguage `"en"` or `"fr"` — same pattern as
 *   [asrEngine]: written immediately through
 *   [SettingsViewModel.setLanguage] so the user does not have to
 *   tap Save before the FAB picks up the new language.
 * @property testing `true` while [SettingsViewModel.testConnection]
 *   is in flight; the screen greys out the button and shows a
 *   spinner / "Testing…" line.
 * @property testResult last connection-test outcome (see
 *   [ConnectionTestResult]); `null` before the first test.
 * @property availableModels model ids returned by the most recent
 *   successful `listModels` call; the screen renders them as
 *   `SuggestionChip`s that fill the Model field on tap.
 * @property saved `true` for one snapshot after [SettingsViewModel.save]
 *   completes; the screen reads it once (via `LaunchedEffect`) to
 *   pop the toast/snackbar and reset it.
 * @property frenchModelInstalled `true` when the French bundle is
 *   fully on disk; the "Delete French model" text button is gated
 *   on this when English is the active language.
 * @property selectedLanguageModelInstalled `true` when the ASR
 *   bundle for the currently-selected language and engine is
 *   installed. Drives the "Model installed" / "Model not
 *   downloaded" caption under the language selector.
 * @property selectedLanguageModelMissingBytes total on-disk size
 *   (in bytes) of the ASR bundle that would need to be
 *   downloaded to make `selectedLanguageModelInstalled` true.
 *   Zero when the bundle is already installed.
 */
data class SettingsUiState(
    val form: SettingsForm = SettingsForm(),
    val asrEngine: String = "npu",
    val asrLanguage: String = "en",
    val testing: Boolean = false,
    val testResult: ConnectionTestResult? = null,
    val availableModels: List<String> = emptyList(),
    val saved: Boolean = false,
    val frenchModelInstalled: Boolean = false,
    val selectedLanguageModelInstalled: Boolean = true,
    val selectedLanguageModelMissingBytes: Long = 0L,
    /** Participants new conversations start with (saved by "Set as default"); `null` is anyone. */
    val defaultParticipants: List<String>? = null,
    /** [com.convoscribe.settings.DisplayMode.key]; written immediately, like the engine. */
    val displayMode: String = DisplayMode.AUTO.key,
    val dynamicColor: Boolean = false,
    /** The floating recording control over other apps (issue #7); written immediately. */
    val floatingControl: Boolean = true,
    /** [com.convoscribe.settings.FloatingShape.key], or `null` for the device default (issue #21); written immediately. */
    val floatingShape: String? = null,
    /** Check GitHub for a newer release on start (issue #15); written immediately. */
    val updateCheckAuto: Boolean = true,
    /** Offer pre-releases too (issue #15); written immediately. */
    val updateIncludePrereleases: Boolean = false,
    /** [com.convoscribe.settings.SpeakerIdMode.key] (issue #11); written immediately. */
    val speakerIdMode: String = SpeakerIdMode.AUTO.key,
    /** Voice model key ([ModelCatalog.diarizationEmbeddings]); written immediately. */
    val diarizationEmbedding: String = DEFAULT_DIARIZATION_EMBEDDING,
    /** Whether [diarizationEmbedding]'s model is downloaded. */
    val diarizationEmbeddingInstalled: Boolean = true,
    /** Segmentation step in seconds; written immediately. */
    val diarizationStepSeconds: Float = DEFAULT_DIARIZATION_STEP_SECONDS,
    /** Summary template new conversations start with (issue #27); written immediately. */
    val defaultSummaryTemplate: String = SummaryTemplates.MEETING_KEY,
    /** Built-in and user templates, for the default template picker. */
    val summaryTemplates: List<SummaryTemplate> = SummaryTemplates.BUILT_IN,
    /** The noise cleanup model is downloaded (issue #37). */
    val cleanupInstalled: Boolean = false,
    /** The semantic search model is downloaded (issue #61). */
    val semanticInstalled: Boolean = false,
    /** Sessions indexed for semantic search, and all sessions (issue #61). */
    val semanticProgress: IndexProgress = IndexProgress(0, 0),
    /** New conversations clean their audio live (issue #37); written immediately. */
    val cleanAudioDefault: Boolean = false,
    /** Notes of marks are written once speakers are identified (issue #48); written immediately. */
    val autoMarkNotes: Boolean = true,
    /** Conversations are named from their summary (issue #32); written immediately. */
    val autoTitle: Boolean = true,
    /** A transcription server URL is saved. */
    val serverConfigured: Boolean = false,
    /** Improve recordings and imports on the server (issue #58); written immediately. */
    val serverAuto: Boolean = false,
    /** `true` while [SettingsViewModel.testServer] is in flight. */
    val serverTesting: Boolean = false,
    /** Last server test outcome; `null` before the first. */
    val serverTestResult: ConnectionTestResult? = null,
    /** Model ids the server listed in the last successful test. */
    val serverModels: List<String> = emptyList()
)

/**
 * ViewModel for the Settings screen. Owns the in-flight form so a
 * configuration change does not lose what the user typed.
 *
 *  - [update] replaces the form wholesale — the screen does that
 *    on every text field;
 *  - [save] writes the form through [SettingsRepository.update]
 *    (the validator in the repo clamps numbers, trims the URL and
 *    rejects unknown ASR engine / language values), persists the
 *    API key via [SettingsRepository.setApiKey], and sets
 *    [SettingsUiState.saved] so the screen can toast;
 *  - [testConnection] saves first so the user is sure the values
 *    they see are what is tested, then calls
 *    [ChatClient.listModels] and stores either `"Connected – N
 *    models: a, b, c"` (first 5 ids) or `"Failed: <message>"`.
 *
 * [setEngine] and [setLanguage] update immediately: the segmented
 * buttons have their own save-on-click semantics, so the user does
 * not have to remember to tap Save before running Engine check or
 * before tapping the home FAB. Both methods synchronously refresh
 * the model-status fields so a test (or a recomposition) that
 * reads `state.value` immediately after the click sees the
 * post-update model status without waiting for the settings-flow
 * collector to fire.
 *
 * [deleteFrenchModel] removes the French bundle from disk via
 * [ModelStore.delete]; the screen calls it from the "Delete
 * French model" text button.
 *
 * @param repo persisted settings; written by `save`, read on
 *   construction to seed the form.
 * @param chatClient LLM client whose `listModels` powers the
 *   connection test. Injected so tests can pass a fake.
 * @param listServerModels the transcription server's model ids, for
 *   [testServer] (issue #58).
 * @param modelStore on-disk model store, used to check
 *   installation and to free space when the user deletes the
 *   French bundle.
 */
class SettingsViewModel(
    private val repo: SettingsRepository,
    private val chatClient: ChatClient,
    private val modelStore: ModelStore,
    templates: SummaryTemplateStore,
    private val listServerModels: suspend () -> List<String> = { emptyList() },
    private val semanticIndex: SemanticIndexRunner? = null,
    private val onSemanticModelChanged: () -> Unit = {}
) : ViewModel() {

    private val _state: MutableStateFlow<SettingsUiState>
    val state: StateFlow<SettingsUiState>

    init {
        val current = repo.settings.value
        val form = SettingsForm.from(current, repo.apiKey(), repo.asrServerKey())
        val initial = computeModelState(
            current = current,
            base = SettingsUiState(
                form = form,
                asrEngine = current.asrEngine,
                asrLanguage = current.asrLanguage
            )
        )
        _state = MutableStateFlow(initial)
        state = _state.asStateFlow()
        // Mirror settings changes through the state so a Save
        // (or any other writer) re-evaluates the model-status
        // fields without the screen needing to re-read. The
        // `setLanguage` / `setEngine` callers also update the
        // state synchronously so the recomposition that follows
        // the click already sees the fresh values; the collector
        // here is the safety net for Save (which writes the
        // entire settings blob at once).
        viewModelScope.launch {
            repo.settings.collect { settings ->
                _state.value = computeModelState(settings, _state.value)
            }
        }
        viewModelScope.launch {
            templates.observeAll().collect { list -> _state.value = _state.value.copy(summaryTemplates = list) }
        }
    }

    /**
     * Replace the in-memory form with [form]. The next save
     * applies this snapshot; keystrokes call this every time.
     */
    fun update(form: SettingsForm) {
        _state.value = _state.value.copy(form = form)
    }

    /**
     * Save the chosen ASR engine and language to the repo
     * immediately (the segmented buttons are autonomous) and
     * persist the current form via [SettingsRepository.update] +
     * the encrypted `setApiKey`. The numeric `maxChars` is parsed
     * with `toIntOrNull() ?: <current persisted value>` so an
     * empty or non-numeric field does not blank it out.
     *
     * Sets `saved = true` on the resulting state so the screen
     * can show a "Saved" toast/snackbar once (the screen is
     * responsible for resetting it).
     */
    fun save() {
        val form = _state.value.form
        val current = repo.settings.value
        val parsedChars = form.maxChars.toIntOrNull() ?: current.maxTranscriptChars

        repo.update { previous ->
            previous.copy(
                llmBaseUrl = form.baseUrl,
                llmModel = form.model,
                maxTranscriptChars = parsedChars,
                numThreads = form.numThreads,
                numSpeakers = form.numSpeakers,
                clusterThreshold = form.clusterThreshold,
                speakerMatchThreshold = form.speakerMatchThreshold,
                asrLanguage = _state.value.asrLanguage,
                asrServerUrl = form.serverUrl,
                asrServerModel = form.serverModel
            )
        }
        if (_state.value.asrEngine != current.asrEngine) {
            repo.update { previous ->
                previous.copy(asrEngine = _state.value.asrEngine, asrEngineChosen = true)
            }
        }
        repo.setApiKey(form.apiKey)
        repo.setAsrServerKey(form.serverKey)

        // Re-seed the form from the freshly persisted settings so
        // the screen shows canonical values (e.g. trailing-slash
        // stripped, out-of-range maxChars clamped). The model-status
        // fields are re-evaluated from the on-disk check below so
        // a manual delete between two saves reflects immediately.
        val refreshed = repo.settings.value
        _state.value = computeModelState(
            current = refreshed,
            base = _state.value.copy(
                form = SettingsForm.from(refreshed, repo.apiKey(), repo.asrServerKey()),
                saved = true
            )
        )
    }

    /**
     * Mark the engine as `"npu"` or `"cpu"` and persist it
     * immediately so the user can hit Engine check without
     * touching Save. The update is delegated to
     * [SettingsRepository.update] which only accepts the two
     * documented values. The model's on-disk state is refreshed
     * synchronously so the next recomposition already shows the
     * correct "Model installed / not downloaded" caption.
     */
    fun setEngine(engine: String) {
        if (engine != "npu" && engine != "cpu") return
        if (_state.value.asrEngine == engine) return
        _state.value = computeModelState(
            current = repo.settings.value.copy(asrEngine = engine),
            base = _state.value.copy(asrEngine = engine)
        )
        repo.update { previous -> previous.copy(asrEngine = engine, asrEngineChosen = true) }
    }

    /**
     * Mark the language as `"en"` or `"fr"` and persist it
     * immediately. The screen wires the segmented button to this
     * method so the home FAB picks up the new language without
     * requiring the user to tap Save first.
     *
     * `ModelCatalog.requiredFor` then re-derives the right ASR
     * bundle on the next read; for French the engine has no NPU
     * bundle so `effectiveEngine` snaps back to CPU and the
     * engine selector is unchanged (the user can leave it on
     * NPU; it just won't apply to a French session). The model
     * status is refreshed synchronously.
     */
    fun setLanguage(language: String) {
        if (language != "en" && language != "fr") return
        if (_state.value.asrLanguage == language) return
        _state.value = computeModelState(
            current = repo.settings.value.copy(asrLanguage = language),
            base = _state.value.copy(asrLanguage = language)
        )
        repo.update { previous -> previous.copy(asrLanguage = language) }
    }

    /** Back to "anyone" for new conversations' expected participants. */
    fun resetDefaultParticipants() {
        repo.update { it.copy(defaultParticipants = null) }
        _state.value = _state.value.copy(defaultParticipants = null)
    }

    /**
     * Remove the French ASR bundle from disk. Called from the
     * "Delete French model" text button in Settings when the
     * user wants to free up the ≈71 MB that the French bundle
     * occupies. No-op when the bundle isn't installed.
     *
     * The disk removal is small (`deleteRecursively` on a single
     * bundle directory under `filesDir/models/`) so we run it
     * directly on `viewModelScope` without a dispatcher switch —
     * the deletion itself is fast and bypassing `Dispatchers.IO`
     * keeps unit tests deterministic (a real `Dispatchers.IO`
     * thread escapes `StandardTestDispatcher`'s `advanceUntilIdle`).
     * The post-check then updates `frenchModelInstalled` so the
     * button disappears from the UI without waiting for the
     * settings flow collector to fire (it would only fire on a
     * settings write, which a pure delete never triggers).
     */
    fun deleteFrenchModel() {
        viewModelScope.launch {
            modelStore.delete(ModelCatalog.ASR_FR)
            _state.value = _state.value.copy(
                frenchModelInstalled = modelStore.isInstalled(ModelCatalog.ASR_FR),
                selectedLanguageModelInstalled = modelStore.isInstalled(
                    ModelCatalog.asrFor(_state.value.asrLanguage, _state.value.asrEngine)
                ),
                selectedLanguageModelMissingBytes = computeMissingBytes(
                    _state.value.asrLanguage, _state.value.asrEngine
                )
            )
        }
    }

    /**
     * Persist the display mode ([DisplayMode.key]) immediately; the
     * activity re-themes as soon as the setting changes. An unknown
     * key is ignored.
     */
    fun setDisplayMode(key: String) {
        if (DisplayMode.fromKey(key) == null) return
        repo.update { it.copy(displayMode = key) }
    }

    /** Persist the "Use device colours" switch immediately. */
    fun setDynamicColor(enabled: Boolean) {
        repo.update { it.copy(dynamicColor = enabled) }
    }

    /**
     * Persist the floating control switch immediately. Turning it on
     * here is where the user meets the permission explanation, so the
     * Record screen does not ask again.
     */
    fun setFloatingControl(enabled: Boolean) {
        repo.update { it.copy(floatingControl = enabled, overlayPromptShown = it.overlayPromptShown || enabled) }
    }

    /** Persist the floating control's shape immediately (issue #21). */
    fun setFloatingShape(shape: String) {
        repo.update { it.copy(floatingShape = shape) }
    }

    /**
     * Persist *Identify speakers* immediately (issue #11). Switching
     * starts no work at once: pending sessions wait for the next
     * charge, or for the user.
     */
    fun setSpeakerIdMode(mode: String) {
        repo.update { it.copy(speakerIdMode = mode) }
    }

    /**
     * Persist the speaker identification voice model immediately; it
     * applies from the next identification. A model not downloaded yet
     * is downloaded from the screen.
     */
    fun setDiarizationEmbedding(key: String) {
        repo.update { it.copy(diarizationEmbedding = key) }
    }

    /** Persist the speaker identification step immediately; it applies from the next identification. */
    fun setDiarizationStep(seconds: Float) {
        repo.update { it.copy(diarizationStepSeconds = seconds) }
    }

    /** Persist *Clean new recordings* immediately (issue #37). */
    fun setCleanAudioDefault(enabled: Boolean) {
        repo.update { it.copy(cleanAudioDefault = enabled) }
    }

    /**
     * Re-read which models are on disk, after a download finished
     * outside this screen's writes (the cleanup model, issue #37).
     */
    fun refreshModels() {
        val before = _state.value.semanticInstalled
        _state.value = computeModelState(repo.settings.value, _state.value)
        if (_state.value.semanticInstalled != before) onSemanticModelChanged()
        refreshSemanticProgress()
    }

    /** Re-count the sessions indexed for semantic search (issue #61). */
    fun refreshSemanticProgress() {
        val index = semanticIndex ?: return
        viewModelScope.launch {
            val progress = index.progress()
            _state.update { it.copy(semanticProgress = progress) }
        }
    }

    /** Remove the semantic search model and its index (issue #61); Ask across sessions then matches words only. */
    fun deleteSemanticModel() {
        viewModelScope.launch {
            modelStore.delete(ModelCatalog.TEXT_EMBEDDING_GEMMA)
            semanticIndex?.clear()
            refreshModels()
        }
    }

    /** Remove the noise cleanup model (issue #37); cleaned sessions keep their cleaned audio. */
    fun deleteCleanupModel() {
        viewModelScope.launch {
            modelStore.delete(ModelCatalog.CLEANUP)
            refreshModels()
        }
    }

    /** Persist *Write notes for marks automatically* immediately (issue #48). */
    fun setAutoMarkNotes(enabled: Boolean) {
        repo.update { it.copy(autoMarkNotes = enabled) }
    }

    /** Persist *Name conversations automatically* immediately (issue #32). */
    fun setAutoTitle(enabled: Boolean) {
        repo.update { it.copy(autoTitle = enabled) }
    }

    /** Persist *Improve recordings on the server* immediately (issue #58). */
    fun setServerAuto(enabled: Boolean) {
        repo.update { it.copy(asrServerAuto = enabled) }
    }

    /** Persist the default summary template immediately (issue #27). */
    fun setDefaultSummaryTemplate(key: String) {
        repo.update { it.copy(defaultSummaryTemplate = key) }
    }

    /** Persist *Check for updates automatically* immediately (issue #15). */
    fun setUpdateCheckAuto(enabled: Boolean) {
        repo.update { it.copy(updateCheckAuto = enabled) }
    }

    /** Persist *Include pre-releases* immediately (issue #15). */
    fun setUpdateIncludePrereleases(enabled: Boolean) {
        repo.update { it.copy(updateIncludePrereleases = enabled) }
    }

    /**
     * Persist the current form, then call [ChatClient.listModels].
     * Records the outcome as [ConnectionTestResult.Connected] (with
     * the first five ids) or [ConnectionTestResult.Failed] (see
     * [com.convoscribe.llm.toUserMessage]) on [SettingsUiState.testResult].
     * `availableModels` is populated only on success.
     *
     * The `saved` flag is not affected — connection-test is a
     * read.
     */
    fun testConnection() {
        if (_state.value.testing) return
        save()
        _state.value = _state.value.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val result = runCatching { chatClient.listModels() }
            _state.value = _state.value.copy(
                testing = false,
                testResult = result.fold(
                    onSuccess = { models ->
                        ConnectionTestResult.Connected(count = models.size, models = models.take(5))
                    },
                    onFailure = { failure ->
                        Log.w(TAG, "settings: LLM connection test failed", failure)
                        ConnectionTestResult.Failed(failure.toUserMessage())
                    }
                ),
                availableModels = result.getOrDefault(emptyList())
            )
        }
    }

    /**
     * Like [testConnection], for the transcription server (issue #58):
     * save, then list its models.
     */
    fun testServer() {
        if (_state.value.serverTesting) return
        save()
        _state.value = _state.value.copy(serverTesting = true, serverTestResult = null)
        viewModelScope.launch {
            val result = runCatching { listServerModels() }
            _state.value = _state.value.copy(
                serverTesting = false,
                serverTestResult = result.fold(
                    onSuccess = { models ->
                        ConnectionTestResult.Connected(count = models.size, models = models.take(5))
                    },
                    onFailure = { failure ->
                        Log.w(TAG, "settings: transcription server test failed", failure)
                        ConnectionTestResult.Failed(
                            (failure as? ServerAsrException)?.message ?: failure.toUserMessage()
                        )
                    }
                ),
                serverModels = result.getOrDefault(emptyList())
            )
        }
    }

    /**
     * Reset `saved` after the screen has shown its confirmation.
     * Called from the screen's `LaunchedEffect(true)` once.
     */
    fun acknowledgeSaved() {
        if (_state.value.saved) {
            _state.value = _state.value.copy(saved = false)
        }
    }

    /**
     * Compute the model-status fields from the supplied settings
     * and the on-disk check. Used both by the settings-flow
     * collector (which receives a real [AppSettings]) and by
     * `setLanguage` / `setEngine` (which synthesise the next
     * settings snapshot from the already-known state). The
     * `base` snapshot is updated in-place so the rest of the UI
     * state — `form`, `saved`, `testing`, … — is preserved
     * across a refresh.
     */
    private fun computeModelState(current: AppSettings, base: SettingsUiState): SettingsUiState {
        val bundle = ModelCatalog.asrFor(current.asrLanguage, current.asrEngine)
        val installed = modelStore.isInstalled(bundle)
        return base.copy(
            asrEngine = current.asrEngine,
            asrLanguage = current.asrLanguage,
            defaultParticipants = current.defaultParticipants,
            displayMode = current.displayMode,
            dynamicColor = current.dynamicColor,
            floatingControl = current.floatingControl,
            floatingShape = current.floatingShape,
            updateCheckAuto = current.updateCheckAuto,
            updateIncludePrereleases = current.updateIncludePrereleases,
            speakerIdMode = current.speakerIdMode,
            diarizationEmbedding = current.diarizationEmbedding,
            diarizationEmbeddingInstalled = modelStore.isInstalled(
                ModelCatalog.embeddingFor(current.diarizationEmbedding)
            ),
            diarizationStepSeconds = current.diarizationStepSeconds,
            defaultSummaryTemplate = current.defaultSummaryTemplate,
            frenchModelInstalled = modelStore.isInstalled(ModelCatalog.ASR_FR),
            cleanupInstalled = modelStore.isInstalled(ModelCatalog.CLEANUP),
            semanticInstalled = modelStore.isInstalled(ModelCatalog.TEXT_EMBEDDING_GEMMA),
            cleanAudioDefault = current.cleanAudioDefault,
            autoMarkNotes = current.autoMarkNotes,
            autoTitle = current.autoTitle,
            serverConfigured = current.asrServerConfigured,
            serverAuto = current.asrServerAuto,
            selectedLanguageModelInstalled = installed,
            selectedLanguageModelMissingBytes = if (installed) 0L else bundle.totalBytes
        )
    }

    /**
     * Compute the bytes that need to be downloaded to make the
     * current language's ASR bundle install-ready. Used after
     * deletes (which do not write through `repo.update`) so the
     * displayed "Model not downloaded (N MB)" string reflects
     * the most recent on-disk value.
     */
    private fun computeMissingBytes(language: String, engine: String): Long {
        val bundle = ModelCatalog.asrFor(language, engine)
        return if (modelStore.isInstalled(bundle)) 0L else bundle.totalBytes
    }
}
