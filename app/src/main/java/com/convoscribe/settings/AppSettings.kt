package com.convoscribe.settings

import com.convoscribe.llm.SummaryTemplates

/**
 * Persisted user preferences used by ASR, diarization and the LLM
 * client. [numSpeakers] == -1 means the diarizer should pick the
 * number of speakers automatically; any other value (1..10) pins
 * the count.
 *
 * [asrEngine] is the speech-recognition engine: `"npu"` (Nemotron on
 * the Hexagon NPU, English only) or `"cpu"` (Kroko zipformer, English
 * and French). An unsupported value is rejected by
 * [SettingsRepository.update] so a bad write keeps the previous value.
 * [asrEngineChosen] is `true` once the user picked the engine in
 * Settings; until then the engine follows the device default (CPU,
 * or NPU once the engine check has seen it work, see
 * [com.convoscribe.enginecheck.NpuAvailability]).
 *
 * [asrLanguage], [numSpeakers] and [defaultParticipants] are only the
 * defaults a new conversation starts from (issue #6): each session
 * stores its own values, chosen on the Record screen.
 * [defaultParticipants] is a list of enrolled names, or `null` for
 * anyone.
 *
 * [displayMode] is a [DisplayMode.key] (`"auto"`, `"standard"` or
 * `"eink"`); [dynamicColor] opts into the wallpaper-based colours on
 * Android 12+ instead of the brand ones (ignored in e-ink mode).
 *
 * [floatingControl] shows the floating recording control over other
 * apps while a session is open (issue #7); it also needs the "Display
 * over other apps" permission. [overlayPromptShown] is `true` once the
 * user has been asked for that permission, so they are asked only once.
 * [floatingShape] is a [FloatingShape.key], or `null` until the user
 * picks one, when [FloatingShape.resolve] gives the device default
 * (issue #21).
 *
 * [updateCheckAuto] lets the app ask GitHub for a newer release when
 * it comes to the foreground and from a periodic background job, at
 * most once every 30 minutes (issues #15, #41); with it off the app makes
 * no update request unless the user taps *Check for updates*.
 * [updateIncludePrereleases] also offers releases marked pre-release.
 *
 * [speakerIdMode] is a [SpeakerIdMode.key] (`"auto"`, `"charging"` or
 * `"manual"`): when speakers are identified after a transcript is done
 * (issue #11).
 *
 * [semanticIndexMode] is a [SemanticIndexMode.key] (`"charging"` or
 * `"anytime"`): when sessions are indexed for semantic search (issue #65).
 *
 * [defaultSummaryTemplate] is the summary template key a new
 * conversation starts with (issue #27, see
 * [com.convoscribe.llm.SummaryTemplates]); a key whose user template
 * was deleted resolves to Meeting.
 *
 * [cleanAudioDefault] starts a new conversation with live noise
 * cleanup on (issue #37); it only applies once the cleanup model is
 * downloaded, and the Record screen can change it per conversation.
 *
 * [autoMarkNotes] writes the full note of each mark (issue #48) once a
 * session's speakers are identified, when an LLM endpoint is set up;
 * the Summary tab can always write them by hand.
 *
 * [autoTitle] names a conversation from its summary (issue #32) while
 * it still has its date-based title, when an LLM endpoint is set up.
 *
 * [clusterThreshold] defaults to the value chosen with
 * `scripts/eval/eval_speakers.py --bench` (see its README).
 *
 * [diarizationEmbedding] is the voice model speaker identification
 * clusters with, a key of
 * [com.convoscribe.models.ModelCatalog.diarizationEmbeddings]
 * (`"resnet34"`, the default, or `"campplus"`); known voices are always
 * matched with the default one. [diarizationStepSeconds] is how far the
 * segmentation model's 10 s window moves each step: 1 s (the default)
 * looks at each moment ten times, a longer step fewer times and is
 * faster. Both are there to compare speed and accuracy.
 *
 * [asrServerUrl] is the OpenAI-style base URL of a self-hosted
 * transcription server (issue #58), typically ending in `/v1`; blank
 * means none. Plain `http://` is allowed, as for the LLM endpoint,
 * and Settings warns about it. [asrServerModel] is the model asked
 * for (blank: the server's first one); its API key lives in
 * [SecretStore]. [asrServerAuto] sends each stopped recording and each
 * import to the server, keeping the device transcript if that fails.
 *
 * Field defaults match a fresh install on first launch; values are
 * written to a `SharedPreferences` file by [SettingsRepository] and
 * reloaded on construction.
 */
data class AppSettings(
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    val maxTranscriptChars: Int = 24000,
    val asrLanguage: String = "en",
    val asrEngine: String = "cpu",
    val asrEngineChosen: Boolean = false,
    val numThreads: Int = 4,
    val asrServerUrl: String = "",
    val asrServerModel: String = "",
    val asrServerAuto: Boolean = false,
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
    val speakerMatchThreshold: Float = 0.5f,
    val speakerIdMode: String = SpeakerIdMode.AUTO.key,
    val semanticIndexMode: String = SemanticIndexMode.CHARGING.key,
    val diarizationEmbedding: String = DEFAULT_DIARIZATION_EMBEDDING,
    val diarizationStepSeconds: Float = DEFAULT_DIARIZATION_STEP_SECONDS,
    val defaultParticipants: List<String>? = null,
    val defaultSummaryTemplate: String = SummaryTemplates.MEETING_KEY,
    val cleanAudioDefault: Boolean = false,
    val autoMarkNotes: Boolean = true,
    val autoTitle: Boolean = true,
    val displayMode: String = DisplayMode.AUTO.key,
    val dynamicColor: Boolean = false,
    val floatingControl: Boolean = true,
    val floatingShape: String? = null,
    val overlayPromptShown: Boolean = false,
    val updateCheckAuto: Boolean = true,
    val updateIncludePrereleases: Boolean = false
) {
    /** A transcription server is set up (issue #58). */
    val asrServerConfigured: Boolean get() = asrServerUrl.isNotBlank()

    /** Stopped recordings and imports go to the server. */
    val asrServerAutoActive: Boolean get() = asrServerAuto && asrServerConfigured
}

/**
 * Whether [url] sends what it is given unencrypted (`http://`), for the
 * warning under the transcription server address (issue #58).
 */
fun isUnencryptedUrl(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/** [AppSettings.diarizationEmbedding] of a fresh install: WeSpeaker ResNet34. */
const val DEFAULT_DIARIZATION_EMBEDDING = "resnet34"

/** [AppSettings.diarizationStepSeconds] of a fresh install: sherpa-onnx's own 1 s. */
const val DEFAULT_DIARIZATION_STEP_SECONDS = 1f

/** Steps offered in Settings → Speakers, in seconds. */
val DIARIZATION_STEPS_SECONDS: List<Float> = listOf(1f, 2f, 3f, 5f)
