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
 * (issue #11). [speakerIdWhileRecording] lets them be identified while a
 * recording is open; off, a run gives way to the recording and is picked
 * up once it ends (issue #123). Like the ASR engine, it depends on the
 * phone, so backups leave it out.
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
 * [autoSummary] summarises a conversation once it is finished (issue
 * #29): its speakers identified, or left for the user to identify. Only
 * when an LLM endpoint is set up and it has no summary yet.
 *
 * [exportFolderUri] is the folder (a Storage Access Framework tree)
 * exports are saved to (issue #29), with [exportFolderName] to show, both
 * blank until one is picked. [exportFormat] (an
 * [com.convoscribe.export.ExportFormat] key), [exportSections] and
 * [exportToFolder] are the Export dialog's last choices. None of them are
 * backed up: the folder's access belongs to this device.
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
 * [syncServerUrl] is the base URL of the self-hosted sync server
 * (issue #68), without `/api/sync/v1`; its key lives in [SecretStore].
 * [syncEnabled] uploads every finished session to it in the background.
 * [syncAudioOnMobileData] lets sync download recordings from other
 * devices on a metered network too (issue #69); otherwise they wait
 * for Wi-Fi.
 *
 * [serverServicesEnabled] is *Use this server's services* (issue #94):
 * the user accepted sending transcripts and recordings to the language
 * model and transcription the sync server provides. It is apart from
 * [syncEnabled]: either can be on without the other. [serverOffer] is
 * what the server last said it provides. [llmSource] and [asrSource]
 * pick, per service, the server's or the user's own (`null` until the
 * services are first turned on, see [withServerServicesOn]). The
 * user's own address, key and model are never changed by any of this.
 * None of these four is backed up.
 *
 * [decisionBaseUrl] is a laya decision service (issue #101), the base
 * URL where `/v1/systemone` lives (blank: none); [decisionModel] is the
 * laya model asked for (blank: laya picks by language); its key lives
 * in [SecretStore]. [decisionSource] picks the sync server's or the
 * user's own, like [llmSource]. [decisionForTalkingPoints] is *Check
 * talking points with the decision model*: off sends the checks to the
 * language model even when a decision service is set up. It is off by
 * default and experimental (issue #136): on real recordings laya as
 * shipped ticked points before they came up.
 *
 * [talkingPointLowReasoning] asks the user's own language model for
 * little reasoning in talking-point checks, with
 * [talkingPointExtraFields] (a JSON object, blank for none) added to
 * those requests for models with their own switch (issue #136). The
 * sync server's model is asked the server's way instead.
 *
 * [liveAssistDefault] turns *Live assist* on when a recording starts
 * (issue #50): questions asked aloud are answered from the
 * conversation and past sessions, which sends recent transcript text
 * to the language model while recording. [liveAssistSemantic] also
 * matches past sessions by meaning for it, with the semantic search
 * model, which costs CPU next to live transcription; it depends on the
 * device, so it is not backed up.
 *
 * Field defaults match a fresh install on first launch; values are
 * written to a `SharedPreferences` file by [SettingsRepository] and
 * reloaded on construction.
 */
data class AppSettings(
    val llmBaseUrl: String = "",
    val llmModel: String = "",
    val maxTranscriptChars: Int = 24000,
    val talkingPointCheckSeconds: Int = SettingsRepository.DEFAULT_TALKING_POINT_CHECK_SECONDS,
    val asrLanguage: String = "en",
    val asrEngine: String = "cpu",
    val asrEngineChosen: Boolean = false,
    val numThreads: Int = 4,
    val asrServerUrl: String = "",
    val asrServerModel: String = "",
    val asrServerAuto: Boolean = false,
    val syncServerUrl: String = "",
    val syncEnabled: Boolean = false,
    val syncAudioOnMobileData: Boolean = false,
    val serverServicesEnabled: Boolean = false,
    val serverOffer: ServerOffer? = null,
    val llmSource: ServiceSource? = null,
    val asrSource: ServiceSource? = null,
    val decisionBaseUrl: String = "",
    val decisionModel: String = "",
    val decisionSource: ServiceSource? = null,
    val decisionForTalkingPoints: Boolean = false,
    val talkingPointLowReasoning: Boolean = false,
    val talkingPointExtraFields: String = "",
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
    val speakerMatchThreshold: Float = 0.5f,
    val speakerIdMode: String = SpeakerIdMode.AUTO.key,
    val speakerIdWhileRecording: Boolean = true,
    val semanticIndexMode: String = SemanticIndexMode.CHARGING.key,
    val diarizationEmbedding: String = DEFAULT_DIARIZATION_EMBEDDING,
    val diarizationStepSeconds: Float = DEFAULT_DIARIZATION_STEP_SECONDS,
    val defaultParticipants: List<String>? = null,
    val defaultSummaryTemplate: String = SummaryTemplates.MEETING_KEY,
    val cleanAudioDefault: Boolean = false,
    val autoMarkNotes: Boolean = true,
    val autoSummary: Boolean = false,
    val exportFolderUri: String = "",
    val exportFolderName: String = "",
    val exportFormat: String = "md",
    val exportSections: String = "transcript,summary,questions",
    val exportToFolder: Boolean = false,
    val autoTitle: Boolean = true,
    val talkingPointsOpenFirst: Boolean = false,
    val liveAssistDefault: Boolean = false,
    val liveAssistSemantic: Boolean = false,
    val displayMode: String = DisplayMode.AUTO.key,
    val dynamicColor: Boolean = false,
    val floatingControl: Boolean = true,
    val floatingShape: String? = null,
    val overlayPromptShown: Boolean = false,
    val updateCheckAuto: Boolean = true,
    val updateIncludePrereleases: Boolean = false
) {
    /** The language model in use is the sync server's (issue #94), whether or not it provides one. */
    val llmFromServer: Boolean
        get() = serverServicesEnabled && llmSource == ServiceSource.SERVER && syncServerUrl.isNotBlank()

    /** Transcription in use is the sync server's (issue #94), whether or not it provides it. */
    val asrFromServer: Boolean
        get() = serverServicesEnabled && asrSource == ServiceSource.SERVER && syncServerUrl.isNotBlank()

    /** Base URL of the language model in use; blank when there is none. */
    val llmBaseUrlInUse: String
        get() = when {
            !llmFromServer -> llmBaseUrl
            serverOffer?.llm != null -> syncServerUrl + SERVER_LLM_PATH
            else -> ""
        }

    /** Model asked of the language model in use. */
    val llmModelInUse: String get() = if (llmFromServer) serverOffer?.llm?.model.orEmpty() else llmModel

    /** A language model is set up: summaries, questions and titles can run. */
    val llmConfigured: Boolean get() = llmBaseUrlInUse.isNotBlank() && llmModelInUse.isNotBlank()

    /** Base URL of the transcription server in use; blank when there is none. */
    val asrServerUrlInUse: String
        get() = when {
            !asrFromServer -> asrServerUrl
            serverOffer?.transcription != null -> syncServerUrl + SERVER_ASR_PATH
            else -> ""
        }

    /** Model asked of the transcription server in use (blank: its first one). */
    val asrServerModelInUse: String
        get() = if (asrFromServer) serverOffer?.transcription?.model.orEmpty() else asrServerModel

    /** A transcription server is set up (issue #58), the user's own or the sync server's. */
    val asrServerConfigured: Boolean get() = asrServerUrlInUse.isNotBlank()

    /** The decision service in use is the sync server's (issue #101), whether or not it provides one. */
    val decisionFromServer: Boolean
        get() = serverServicesEnabled && decisionSource == ServiceSource.SERVER && syncServerUrl.isNotBlank()

    /** Base URL of the decision service in use; blank when there is none. */
    val decisionBaseUrlInUse: String
        get() = when {
            !decisionFromServer -> decisionBaseUrl
            serverOffer?.decision != null -> syncServerUrl + SERVER_DECISION_PATH
            else -> ""
        }

    /** Laya model asked of the decision service in use (blank: laya picks by language). */
    val decisionModelInUse: String
        get() = if (decisionFromServer) serverOffer?.decision?.model.orEmpty() else decisionModel

    /** A decision service is set up (issue #101), the user's own or the sync server's. */
    val decisionConfigured: Boolean get() = decisionBaseUrlInUse.isNotBlank()

    /** Talking points are checked by the decision service rather than the language model. */
    val talkingPointsByDecision: Boolean get() = decisionConfigured && decisionForTalkingPoints

    /** Stopped recordings and imports go to the server. */
    val asrServerAutoActive: Boolean get() = asrServerAuto && asrServerConfigured

    /** Sessions are uploaded to a sync server (issue #68). */
    val syncActive: Boolean get() = syncEnabled && syncServerUrl.isNotBlank()

    /**
     * *Use this server's services* turned on (issue #94). A service
     * whose source was never chosen uses the server's unless the user
     * has an address of their own for it; a chosen source is kept.
     * *Improve recordings on the server* left on from an own server since
     * removed is turned off when transcription first moves to the sync
     * server, so recordings don't start going there unasked.
     */
    fun withServerServicesOn(): AppSettings {
        val firstOnServer = asrSource == null && asrServerUrl.isBlank()
        return copy(
            serverServicesEnabled = true,
            llmSource = llmSource ?: if (llmBaseUrl.isBlank()) ServiceSource.SERVER else ServiceSource.OWN,
            asrSource = asrSource ?: if (firstOnServer) ServiceSource.SERVER else ServiceSource.OWN,
            decisionSource = decisionSource
                ?: if (decisionBaseUrl.isBlank()) ServiceSource.SERVER else ServiceSource.OWN,
            asrServerAuto = asrServerAuto && !firstOnServer
        )
    }
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
