package com.convoscribe.settings

import android.content.SharedPreferences
import com.convoscribe.data.ParticipantsCodec
import com.convoscribe.llm.SummaryTemplates
import com.convoscribe.models.ModelCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read/write façade for [AppSettings]. The plain preferences hold
 * every field except the API key; that lives in [SecretStore] (an
 * encrypted file in production).
 *
 * All writes go through [update] which clamps values to the ranges
 * the engines and the LLM client can actually use, then mirrors the
 * result into [settings] so observers re-render.
 *
 * The ASR engine is persisted only once the user has chosen one
 * ([AppSettings.asrEngineChosen]); until then it comes from
 * [defaultEngine], so a device whose NPU has not been verified starts
 * on the CPU and a later passing NPU check can still switch it.
 *
 * @param defaultEngine engine to use while the user has not chosen
 *   one. Production passes "npu if the engine check verified it on
 *   this device, else cpu".
 */
class SettingsRepository(
    private val prefs: SharedPreferences,
    private val secrets: SecretStore,
    private val defaultEngine: () -> String = { "cpu" }
) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Apply [transform] to the current settings, validate every
     * field and persist the result. The returned value is the new
     * state. The validation rules are:
     *  - `maxTranscriptChars` clamped to 2000..400000
     *  - `numThreads` clamped to 1..8
     *  - `numSpeakers` must be -1 or in 1..10; 0 or anything < -1
     *    becomes -1
     *  - thresholds clamped to 0.05..0.95
     *  - `llmBaseUrl` trimmed and trailing `/` removed
     *  - `asrServerUrl` trimmed and trailing `/` removed;
     *    `asrServerModel` trimmed
     *  - `syncServerUrl` trimmed and trailing `/` removed
     *  - `defaultParticipants` trimmed and de-duplicated; empty
     *    becomes `null` (anyone)
     *  - `asrLanguage` must be "en" or "fr"; anything else keeps
     *    the previous value (no exception, no overwrite)
     *  - `asrEngine` must be "npu" or "cpu"; anything else keeps
     *    the previous value (no exception, no overwrite); changing
     *    it marks the engine as chosen, so it is persisted from then on
     *  - `displayMode` must be a [DisplayMode.key]; anything else
     *    keeps the previous value
     *  - `speakerIdMode` must be a [SpeakerIdMode.key]; anything else
     *    keeps the previous value
     *  - `semanticIndexMode` must be a [SemanticIndexMode.key]; anything
     *    else keeps the previous value
     *  - `diarizationEmbedding` must be a key of
     *    [ModelCatalog.diarizationEmbeddings]; anything else keeps the
     *    previous value
     *  - `diarizationStepSeconds` clamped to 0.5..10 (the window is 10 s)
     *  - `floatingShape` must be a [FloatingShape.key] or `null`;
     *    anything else keeps the previous value
     *  - `defaultSummaryTemplate` trimmed; blank becomes Meeting. A
     *    deleted user template is not caught here (they live in
     *    Room); it resolves to Meeting when used
     */
    fun update(transform: (AppSettings) -> AppSettings) {
        val current = _settings.value
        val raw = transform(current)
        val next = raw.validatedAgainst(current)
        prefs.edit().apply {
            putString(KEY_LLM_BASE_URL, next.llmBaseUrl)
            putString(KEY_LLM_MODEL, next.llmModel)
            putInt(KEY_MAX_TRANSCRIPT_CHARS, next.maxTranscriptChars)
            putString(KEY_ASR_LANGUAGE, next.asrLanguage)
            putBoolean(KEY_ASR_ENGINE_CHOSEN, next.asrEngineChosen)
            if (next.asrEngineChosen) putString(KEY_ASR_ENGINE, next.asrEngine) else remove(KEY_ASR_ENGINE)
            putInt(KEY_NUM_THREADS, next.numThreads)
            putString(KEY_ASR_SERVER_URL, next.asrServerUrl)
            putString(KEY_ASR_SERVER_MODEL, next.asrServerModel)
            putBoolean(KEY_ASR_SERVER_AUTO, next.asrServerAuto)
            putString(KEY_SYNC_SERVER_URL, next.syncServerUrl)
            putBoolean(KEY_SYNC_ENABLED, next.syncEnabled)
            putInt(KEY_NUM_SPEAKERS, next.numSpeakers)
            putFloat(KEY_CLUSTER_THRESHOLD, next.clusterThreshold)
            putBoolean(KEY_CLUSTER_THRESHOLD_TUNED, true)
            putFloat(KEY_SPEAKER_MATCH_THRESHOLD, next.speakerMatchThreshold)
            putString(KEY_SPEAKER_ID_MODE, next.speakerIdMode)
            putString(KEY_SEMANTIC_INDEX_MODE, next.semanticIndexMode)
            putString(KEY_DIARIZATION_EMBEDDING, next.diarizationEmbedding)
            putFloat(KEY_DIARIZATION_STEP_SECONDS, next.diarizationStepSeconds)
            putString(KEY_DISPLAY_MODE, next.displayMode)
            putBoolean(KEY_DYNAMIC_COLOR, next.dynamicColor)
            putBoolean(KEY_FLOATING_CONTROL, next.floatingControl)
            if (next.floatingShape == null) {
                remove(
                    KEY_FLOATING_SHAPE
                )
            } else {
                putString(KEY_FLOATING_SHAPE, next.floatingShape)
            }
            putBoolean(KEY_OVERLAY_PROMPT_SHOWN, next.overlayPromptShown)
            putBoolean(KEY_UPDATE_CHECK_AUTO, next.updateCheckAuto)
            putBoolean(KEY_UPDATE_INCLUDE_PRERELEASES, next.updateIncludePrereleases)
            putString(KEY_DEFAULT_SUMMARY_TEMPLATE, next.defaultSummaryTemplate)
            putBoolean(KEY_CLEAN_AUDIO_DEFAULT, next.cleanAudioDefault)
            putBoolean(KEY_AUTO_MARK_NOTES, next.autoMarkNotes)
            putBoolean(KEY_AUTO_TITLE, next.autoTitle)
            val participants = ParticipantsCodec.encode(next.defaultParticipants)
            if (participants == null) {
                remove(
                    KEY_DEFAULT_PARTICIPANTS
                )
            } else {
                putString(KEY_DEFAULT_PARTICIPANTS, participants)
            }
        }.apply()
        _settings.value = next
    }

    /**
     * Re-read the device default engine. Called after the engine
     * check verifies the NPU, so a user who never picked an engine
     * moves to it; an explicit choice is left alone.
     */
    fun refreshDefaults() {
        val current = _settings.value
        if (current.asrEngineChosen) return
        val engine = defaultEngine()
        if (engine != current.asrEngine) _settings.value = current.copy(asrEngine = engine)
    }

    fun apiKey(): String = secrets.getApiKey()

    fun setApiKey(value: String) {
        secrets.setApiKey(value)
    }

    /** API key of the transcription server (issue #58), kept apart from the LLM's. */
    fun asrServerKey(): String = secrets.getAsrServerKey()

    fun setAsrServerKey(value: String) {
        secrets.setAsrServerKey(value)
    }

    /** API key of the sync server (issue #68); never part of what is synced. */
    fun syncKey(): String = secrets.getSyncKey()

    fun setSyncKey(value: String) {
        secrets.setSyncKey(value)
    }

    private fun load(): AppSettings {
        val rawLang = prefs.getString(KEY_ASR_LANGUAGE, null) ?: "en"
        val raw = AppSettings(
            llmBaseUrl = prefs.getString(KEY_LLM_BASE_URL, "") ?: "",
            llmModel = prefs.getString(KEY_LLM_MODEL, "") ?: "",
            maxTranscriptChars = prefs.getInt(KEY_MAX_TRANSCRIPT_CHARS, 24000),
            asrLanguage = rawLang,
            asrEngine = prefs.getString(KEY_ASR_ENGINE, null) ?: "",
            numThreads = prefs.getInt(KEY_NUM_THREADS, 4),
            asrServerUrl = (prefs.getString(KEY_ASR_SERVER_URL, "") ?: "").trim().trimEnd('/'),
            asrServerModel = prefs.getString(KEY_ASR_SERVER_MODEL, "") ?: "",
            asrServerAuto = prefs.getBoolean(KEY_ASR_SERVER_AUTO, false),
            syncServerUrl = (prefs.getString(KEY_SYNC_SERVER_URL, "") ?: "").trim().trimEnd('/'),
            syncEnabled = prefs.getBoolean(KEY_SYNC_ENABLED, false),
            numSpeakers = prefs.getInt(KEY_NUM_SPEAKERS, -1),
            clusterThreshold = loadClusterThreshold(),
            speakerMatchThreshold = prefs.getFloat(KEY_SPEAKER_MATCH_THRESHOLD, 0.5f),
            speakerIdMode = prefs.getString(KEY_SPEAKER_ID_MODE, null) ?: SpeakerIdMode.AUTO.key,
            semanticIndexMode = SemanticIndexMode.of(prefs.getString(KEY_SEMANTIC_INDEX_MODE, null) ?: "").key,
            diarizationEmbedding = prefs.getString(KEY_DIARIZATION_EMBEDDING, null)
                ?.takeIf { it in ModelCatalog.diarizationEmbeddings }
                ?: DEFAULT_DIARIZATION_EMBEDDING,
            diarizationStepSeconds = prefs.getFloat(KEY_DIARIZATION_STEP_SECONDS, DEFAULT_DIARIZATION_STEP_SECONDS),
            defaultParticipants = ParticipantsCodec.decode(prefs.getString(KEY_DEFAULT_PARTICIPANTS, null)),
            displayMode = prefs.getString(KEY_DISPLAY_MODE, null) ?: DisplayMode.AUTO.key,
            dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, false),
            floatingControl = prefs.getBoolean(KEY_FLOATING_CONTROL, true),
            floatingShape = FloatingShape.fromKey(prefs.getString(KEY_FLOATING_SHAPE, null))?.key,
            overlayPromptShown = prefs.getBoolean(KEY_OVERLAY_PROMPT_SHOWN, false),
            updateCheckAuto = prefs.getBoolean(KEY_UPDATE_CHECK_AUTO, true),
            updateIncludePrereleases = prefs.getBoolean(KEY_UPDATE_INCLUDE_PRERELEASES, false),
            defaultSummaryTemplate = summaryTemplateKey(prefs.getString(KEY_DEFAULT_SUMMARY_TEMPLATE, null)),
            cleanAudioDefault = prefs.getBoolean(KEY_CLEAN_AUDIO_DEFAULT, false),
            autoMarkNotes = prefs.getBoolean(KEY_AUTO_MARK_NOTES, true),
            autoTitle = prefs.getBoolean(KEY_AUTO_TITLE, true)
        )
        // On load we don't have a "previous" value; if the persisted
        // asrLanguage is not en/fr we fall back to "en". The stored
        // engine counts only if the user chose it; installs from before
        // the chosen flag existed (engine stored, no flag) count as
        // chosen so nothing changes under them. Otherwise, or if the
        // stored value is invalid, the engine is the device default.
        // Every numeric field is normalised in-place via [normaliseNumeric].
        val lang = if (raw.asrLanguage == "en" || raw.asrLanguage == "fr") raw.asrLanguage else "en"
        val displayMode = if (DisplayMode.fromKey(raw.displayMode) != null) raw.displayMode else DisplayMode.AUTO.key
        val speakerIdMode = SpeakerIdMode.of(raw.speakerIdMode).key
        val storedValid = raw.asrEngine == "npu" || raw.asrEngine == "cpu"
        val chosen = storedValid && prefs.getBoolean(KEY_ASR_ENGINE_CHOSEN, prefs.contains(KEY_ASR_ENGINE))
        val engine = if (chosen) raw.asrEngine else defaultEngine()
        return raw.copy(
            asrLanguage = lang,
            asrEngine = engine,
            asrEngineChosen = chosen,
            displayMode = displayMode,
            speakerIdMode = speakerIdMode
        ).normaliseNumeric().copy(
            llmBaseUrl = raw.llmBaseUrl.trim().trimEnd('/')
        )
    }

    /**
     * Installs from before the threshold was tuned stored the old
     * default (0.5) on every save, whether or not the user touched
     * the slider. Treat that value as "default" once, so they move to
     * [DEFAULT_CLUSTER_THRESHOLD]; any other stored value, and any
     * value saved since (which sets [KEY_CLUSTER_THRESHOLD_TUNED]),
     * is the user's.
     */
    private fun loadClusterThreshold(): Float {
        val stored = prefs.getFloat(KEY_CLUSTER_THRESHOLD, DEFAULT_CLUSTER_THRESHOLD)
        val legacyDefault = !prefs.getBoolean(KEY_CLUSTER_THRESHOLD_TUNED, false) && stored == LEGACY_CLUSTER_THRESHOLD
        return if (legacyDefault) DEFAULT_CLUSTER_THRESHOLD else stored
    }

    internal companion object {
        /**
         * Default clustering threshold. 0.75 had the best purity/coverage
         * F1 (with 0.70) over 4 AMI meetings and 12 synthetic 2-4 speaker
         * conversations, and half the speaker over-count of 0.70; 0.5
         * found 2-4x too many speakers.
         */
        const val DEFAULT_CLUSTER_THRESHOLD = 0.75f
        private const val LEGACY_CLUSTER_THRESHOLD = 0.5f
        const val KEY_LLM_BASE_URL = "llmBaseUrl"
        const val KEY_LLM_MODEL = "llmModel"
        const val KEY_MAX_TRANSCRIPT_CHARS = "maxTranscriptChars"
        const val KEY_ASR_LANGUAGE = "asrLanguage"
        const val KEY_ASR_ENGINE = "asrEngine"
        const val KEY_ASR_ENGINE_CHOSEN = "asrEngineChosen"
        const val KEY_NUM_THREADS = "numThreads"
        const val KEY_ASR_SERVER_URL = "asrServerUrl"
        const val KEY_ASR_SERVER_MODEL = "asrServerModel"
        const val KEY_ASR_SERVER_AUTO = "asrServerAuto"
        const val KEY_SYNC_SERVER_URL = "syncServerUrl"
        const val KEY_SYNC_ENABLED = "syncEnabled"
        const val KEY_NUM_SPEAKERS = "numSpeakers"
        const val KEY_CLUSTER_THRESHOLD = "clusterThreshold"
        const val KEY_CLUSTER_THRESHOLD_TUNED = "clusterThresholdTuned"
        const val KEY_SPEAKER_MATCH_THRESHOLD = "speakerMatchThreshold"
        const val KEY_SPEAKER_ID_MODE = "speakerIdMode"
        const val KEY_SEMANTIC_INDEX_MODE = "semanticIndexMode"
        const val KEY_DIARIZATION_EMBEDDING = "diarizationEmbedding"
        const val KEY_DIARIZATION_STEP_SECONDS = "diarizationStepSeconds"
        const val KEY_DEFAULT_PARTICIPANTS = "defaultParticipants"
        const val KEY_DISPLAY_MODE = "displayMode"
        const val KEY_DYNAMIC_COLOR = "dynamicColor"
        const val KEY_FLOATING_CONTROL = "floatingControl"
        const val KEY_FLOATING_SHAPE = "floatingShape"
        const val KEY_OVERLAY_PROMPT_SHOWN = "overlayPromptShown"
        const val KEY_UPDATE_CHECK_AUTO = "updateCheckAuto"
        const val KEY_UPDATE_INCLUDE_PRERELEASES = "updateIncludePrereleases"
        const val KEY_DEFAULT_SUMMARY_TEMPLATE = "defaultSummaryTemplate"
        const val KEY_CLEAN_AUDIO_DEFAULT = "cleanAudioDefault"
        const val KEY_AUTO_MARK_NOTES = "autoMarkNotes"
        const val KEY_AUTO_TITLE = "autoTitle"
    }
}

/**
 * Validate [this] against the previous [previous] settings. Numeric
 * fields are clamped; `llmBaseUrl` is trimmed and stripped of its
 * trailing `/`, and so are `asrServerUrl` and `syncServerUrl`; `asrLanguage` keeps the previous value when it is
 * not "en" or "fr"; `asrEngine` keeps the previous value when it is
 * not "npu" or "cpu"; `displayMode` and `speakerIdMode` keep the
 * previous value when they are not a [DisplayMode.key] /
 * [SpeakerIdMode.key]; `diarizationEmbedding` keeps it when it is not a
 * voice model key; `floatingShape` keeps the previous value when
 * it is neither `null` nor a [FloatingShape.key].
 */
private fun AppSettings.validatedAgainst(previous: AppSettings): AppSettings {
    val lang = if (asrLanguage == "en" || asrLanguage == "fr") asrLanguage else previous.asrLanguage
    val engine = if (asrEngine == "npu" || asrEngine == "cpu") asrEngine else previous.asrEngine
    val mode = if (DisplayMode.fromKey(displayMode) != null) displayMode else previous.displayMode
    val idMode = if (SpeakerIdMode.fromKey(speakerIdMode) != null) speakerIdMode else previous.speakerIdMode
    val indexMode = if (SemanticIndexMode.fromKey(semanticIndexMode) != null) {
        semanticIndexMode
    } else {
        previous.semanticIndexMode
    }
    val embedding = diarizationEmbedding.takeIf { it in ModelCatalog.diarizationEmbeddings }
        ?: previous.diarizationEmbedding
    val shapeValid = floatingShape == null || FloatingShape.fromKey(floatingShape) != null
    val shape = if (shapeValid) floatingShape else previous.floatingShape
    return this.normaliseNumeric().copy(
        asrServerUrl = asrServerUrl.trim().trimEnd('/'),
        asrServerModel = asrServerModel.trim(),
        syncServerUrl = syncServerUrl.trim().trimEnd('/'),
        displayMode = mode,
        speakerIdMode = idMode,
        semanticIndexMode = indexMode,
        diarizationEmbedding = embedding,
        floatingShape = shape,
        defaultSummaryTemplate = summaryTemplateKey(defaultSummaryTemplate),
        llmBaseUrl = llmBaseUrl.trim().trimEnd('/'),
        defaultParticipants = ParticipantsCodec.normalise(defaultParticipants),
        asrLanguage = lang,
        asrEngine = engine,
        // Changing the engine through update() is a choice; saving
        // other fields leaves the engine on the device default.
        asrEngineChosen = asrEngineChosen || engine != previous.asrEngine
    )
}

/** [key] trimmed, or Meeting's key when it is `null` or blank. */
private fun summaryTemplateKey(key: String?): String =
    key?.trim()?.takeIf { it.isNotEmpty() } ?: SummaryTemplates.MEETING_KEY

/**
 * Apply the numeric coercions shared by load() and update().
 * Public for tests so they can double-check the rules without
 * spinning up a [SharedPreferences].
 */
internal fun AppSettings.normaliseNumeric(): AppSettings {
    val speakers = when {
        numSpeakers == -1 -> -1
        numSpeakers < -1 -> -1
        numSpeakers == 0 -> -1
        else -> numSpeakers.coerceIn(1, 10)
    }
    return copy(
        maxTranscriptChars = maxTranscriptChars.coerceIn(2000, 400_000),
        numThreads = numThreads.coerceIn(1, 8),
        numSpeakers = speakers,
        clusterThreshold = clusterThreshold.coerceIn(0.05f, 0.95f),
        speakerMatchThreshold = speakerMatchThreshold.coerceIn(0.05f, 0.95f),
        diarizationStepSeconds = diarizationStepSeconds.takeUnless { it.isNaN() }
            ?.coerceIn(0.5f, 10f) ?: DEFAULT_DIARIZATION_STEP_SECONDS
    )
}
