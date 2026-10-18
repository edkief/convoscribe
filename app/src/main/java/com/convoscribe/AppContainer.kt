package com.convoscribe

import android.content.Context
import androidx.room.Room
import com.convoscribe.asr.TranscriberFactory
import com.convoscribe.audio.AudioExporter
import com.convoscribe.audio.MediaCodecAudioDecoder
import com.convoscribe.audio.MicAudioSource
import com.convoscribe.data.AppDatabase
import com.convoscribe.data.EnrolledSpeakerRepository
import com.convoscribe.data.EnrolledSpeakerStore
import com.convoscribe.data.SessionRepository
import com.convoscribe.diarization.DiarizeSessionUseCase
import com.convoscribe.diarization.DiarizerFactory
import com.convoscribe.diarization.EnrollSpeakerUseCase
import com.convoscribe.diarization.SpeakerEmbedderFactory
import com.convoscribe.engine.sherpa.SherpaDiarizerFactory
import com.convoscribe.engine.sherpa.SherpaSpeakerEmbedderFactory
import com.convoscribe.engine.sherpa.SherpaTranscriberFactory
import com.convoscribe.enginecheck.NpuAvailability
import com.convoscribe.llm.ChatClient
import com.convoscribe.llm.LlmConfig
import com.convoscribe.llm.OpenAiChatClient
import com.convoscribe.models.ModelDownloadManager
import com.convoscribe.models.ModelDownloader
import com.convoscribe.models.ModelStore
import com.convoscribe.models.NetworkWaiter
import com.convoscribe.recording.ConversationOptions
import com.convoscribe.recording.ImportAudioUseCase
import com.convoscribe.recording.PendingImport
import com.convoscribe.recording.RecordingController
import com.convoscribe.recording.RecordingPhase
import com.convoscribe.recording.RetranscribeUseCase
import com.convoscribe.settings.EncryptedSecretStore
import com.convoscribe.settings.SettingsRepository
import com.convoscribe.update.GitHubReleases
import com.convoscribe.update.InstalledApp
import com.convoscribe.update.PackageApkInspector
import com.convoscribe.update.PackageUpdateInstaller
import com.convoscribe.update.PrefsUpdateStore
import com.convoscribe.update.UpdateDownloader
import com.convoscribe.update.UpdateManager
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient

class AppContainer(val context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Where downloaded models live: `filesDir/models/<bundleId>/...`.
     * Constructed at startup so the downloader, recognizer and
     * diarizer all read paths from the same place.
     */
    val modelStore = ModelStore(File(context.filesDir, "models"))

    /**
     * Shared OkHttp client for the model downloader (and, later, the
     * LLM streaming client). Long read timeout because some model
     * downloads take minutes on slow networks; the 15 s connect timeout
     * still surfaces a dead server quickly.
     */
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    /**
     * Downloads the model bundles the user picked in Settings. Bound
     * to [modelStore] so the files land in the same directory the
     * recognizer and diarizer read from. A transient failure waits for
     * the network to come back before it resumes (issue #10).
     */
    val modelDownloader = ModelDownloader(
        client = httpClient,
        store = modelStore,
        awaitNetwork = NetworkWaiter(context)::awaitNetwork
    )

    /**
     * The one model download that runs at a time, outside any screen
     * (issue #10). Started through
     * [com.convoscribe.service.ModelDownloadService]; its
     * [ModelDownloadManager.state] drives the setup screen and the
     * service notification.
     */
    val modelDownloadManager = ModelDownloadManager(modelDownloader, applicationScope)

    /**
     * What the NPU probe has seen on this device: a verified NPU, or
     * the QNN runtime killing its process. Written by the engine check's NPU
     * probe, read by [transcriberFactory] so a recording with the
     * NPU selected fails with a message instead of closing the app,
     * and by [settingsRepository] to pick the default engine. Built
     * first because the settings read it on construction.
     */
    val npuAvailability = NpuAvailability(
        context.getSharedPreferences(NpuAvailability.PREFS_NAME, Context.MODE_PRIVATE)
    )

    /**
     * Persists [com.convoscribe.settings.AppSettings] in
     * `settings` SharedPreferences and the LLM API key in an
     * encrypted file. Read by ASR (language, thread count),
     * diarization (speaker count, thresholds) and the LLM client
     * (base URL, model, API key).
     */
    val settingsRepository = SettingsRepository(
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE),
        secrets = EncryptedSecretStore(context),
        // CPU everywhere until the engine check has seen the NPU work
        // on this device (the Boox Note Air 6C has no usable NPU).
        defaultEngine = { if (npuAvailability.isVerified()) "npu" else "cpu" }
    )

    /**
     * Room database holding sessions, transcript segments, speaker
     * labels and LLM chat messages. The file lives at
     * `convoscribe.db` inside the app's data directory; the
     * generated schema is committed at `app/schemas/` for future
     * migrations.
     */
    val database: AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        AppDatabase.NAME
    ).build()

    /**
     * High-level session/store façade over [database]. Recordings
     * land in `filesDir/recordings/<id>.wav`; the repository owns
     * the lifetime of those files (created on session start,
     * removed on delete).
     */
    val sessionRepository: SessionRepository = SessionRepository(
        db = database,
        recordingsDir = File(context.filesDir, "recordings").apply { mkdirs() }
    )

    /**
     * Encodes a session's WAV to M4A for sharing and saving. Exports
     * live in the cache directory, which the system may clear; the
     * FileProvider declared in the manifest serves `exports/` to the
     * share sheet.
     */
    val audioExporter: AudioExporter = AudioExporter(
        exportDir = File(context.cacheDir, "exports"),
        openOutput = { uri -> context.contentResolver.openOutputStream(uri) }
    )

    /**
     * V2: enrolled speakers (a name + a reference embedding) are
     * looked up after diarization to give a fresh recording
     * real speaker names. The store is exposed as the interface
     * so later tasks (enrollment UI, post-record name lookup)
     * only depend on `EnrolledSpeakerStore` and stay clear of
     * Room. Backed by the same [database] singleton as the
     * session repository, so an upgrade from v1 lands on v2 in
     * one shot.
     */
    val enrolledSpeakers: EnrolledSpeakerStore = EnrolledSpeakerRepository(
        dao = database.enrolledSpeakerDao()
    )

    /**
     * Factory the recording screen will use (in TASK-12) to ask for
     * a fresh streaming transcriber per session. Wired to the
     * sherpa-onnx engine because that's the only ASR we ship
     * today; the fun interface keeps the recording screen engine-
     * agnostic.
     */
    private val sherpaTranscriberFactory = SherpaTranscriberFactory(
        store = modelStore,
        settings = settingsRepository,
        // `nativeLibraryDir` is the absolute path the Android
        // runtime exposes for the app's packaged `.so` files (e.g.
        // `/data/app/~~XYZ/convoscribe-/lib/arm64-v8a`). The sherpa
        // factory forwards it to `QnnRuntime.ensurePrepared` so the
        // Hexagon DSP libraries can `dlopen` each other on the
        // first NPU build; the CPU engine never reads it.
        //
        // Robolectric leaves `ApplicationInfo.nativeLibraryDir` null
        // because the test process is not a packaged APK; in that
        // case we hand the factory a sentinel empty string. The
        // CPU recognizer is what every test fixture instantiates, so
        // the value is never used and the failure is benign.
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir ?: "",
        npuBlockedReason = npuAvailability::blockedReason
    )

    val transcriberFactory: TranscriberFactory = sherpaTranscriberFactory

    /**
     * [transcriberFactory] pinned to [engine] (`"npu"` or `"cpu"`)
     * whatever Settings say. Used by the engine check, whose two
     * buttons must test their own engine; skips the
     * [npuAvailability] guard so the NPU can be re-checked.
     */
    fun transcriberFactoryFor(engine: String): TranscriberFactory = sherpaTranscriberFactory.withEngine(engine)

    /**
     * Drives a recording session: opens the mic, writes the WAV,
     * feeds the streaming ASR and persists each closed utterance
     * as it arrives. The screen (TASK-12) and the foreground
     * service (TASK-13) call [RecordingController.start] /
     * [RecordingController.stop]; they observe the live state
     * through [RecordingController.state]. A single-threaded
     * `limitedParallelism(1)` dispatcher keeps the recognizer's
     * native state sequential, matching the sherpa-onnx contract.
     */
    val recordingController: RecordingController = RecordingController(
        audioSource = MicAudioSource(),
        transcriberFactory = transcriberFactory,
        store = sessionRepository,
        defaultOptions = { ConversationOptions.fromSettings(settingsRepository.settings.value) },
        asrDispatcher = Dispatchers.Default.limitedParallelism(1)
    )

    /**
     * Factory the post-recording pipeline (TASK-19) uses to ask
     * for a fresh diarizer per session. Wired to the
     * sherpa-onnx engine because that's the only diarizer we ship
     * today; the `fun interface` keeps the pipeline engine-
     * agnostic. The factory refuses to build a [com.convoscribe.diarization.Diarizer]
     * when either model file is missing on disk (it throws
     * `IllegalStateException("Model not installed: <id>")`) so
     * the post-record flow can surface a download hint.
     */
    val diarizerFactory: DiarizerFactory = SherpaDiarizerFactory(
        store = modelStore,
        settings = settingsRepository
    )

    /**
     * Factory the v2 enrollment screen (TASK-32) uses to ask for
     * a fresh speaker embedder each time a user records a voice
     * sample. Wired to the sherpa-onnx engine because that's
     * the only embedding extractor we ship today; the
     * `fun interface` keeps the enrollment screen engine-
     * agnostic. The factory refuses to build a
     * [com.convoscribe.diarization.SpeakerEmbedder] when the
     * `wespeaker_en_voxceleb_resnet34_LM.onnx` file is missing
     * on disk (it throws `IllegalStateException("Model not
     * installed: <id>")`) so the enrollment flow can surface a
     * download hint, mirroring the transcriber and diarizer
     * factories.
     */
    val speakerEmbedderFactory: SpeakerEmbedderFactory = SherpaSpeakerEmbedderFactory(
        store = modelStore,
        settings = settingsRepository
    )

    /**
     * Use case that owns the "turn a TRANSCRIBED session into a
     * DIARIZED one" pipeline. Called from the recording service
     * after the controller's stop() returns, and from the
     * crash-recovery loop on app start (TASK-19). Wired to the
     * sherpa-backed [diarizerFactory] today; the `readSamples`
     * parameter on the use case defaults to [com.convoscribe.audio.WavReader.readMono16k]
     * so the test suite can inject a trivial stub.
     *
     * The optional speaker-name pass (TASK-33) runs after the
     * diarizer completes: collect per-speaker audio, embed each
     * window through [speakerEmbedderFactory], and match the
     * resulting vectors against [enrolledSpeakers]. Wiring is
     * done here so production keeps the embedder model resident
     * for the duration of one diarization but never across
     * multiple sessions.
     */
    val diarizeSessionUseCase = DiarizeSessionUseCase(
        repository = sessionRepository,
        diarizerFactory = diarizerFactory,
        settings = settingsRepository,
        embedderFactory = speakerEmbedderFactory,
        enrolled = enrolledSpeakers
    )

    /**
     * Re-transcribes a session's stored audio in another language and
     * re-identifies its speakers (issue #6). Run by the foreground
     * service; its [RetranscribeUseCase.state] drives the session
     * screen's progress banner.
     */
    val retranscribeUseCase = RetranscribeUseCase(
        repository = sessionRepository,
        transcriberFactory = transcriberFactory,
        diarize = diarizeSessionUseCase
    )

    /**
     * Imports an audio file as a new session (issue #3): decodes it
     * with the platform codecs, transcribes it in the Settings
     * language and identifies speakers. Run by the foreground
     * service; its [ImportAudioUseCase.state] drives the home screen's
     * progress banner. Work files live in `cacheDir/imports/`.
     */
    val importAudioUseCase = ImportAudioUseCase(
        repository = sessionRepository,
        transcriberFactory = transcriberFactory,
        diarize = diarizeSessionUseCase,
        decoder = MediaCodecAudioDecoder(),
        workDir = File(context.cacheDir, "imports"),
        defaultOptions = { ConversationOptions.fromSettings(settingsRepository.settings.value) }
    )

    /**
     * Audio file handed to the app (share sheet, Open with, the home
     * screen's picker) and not yet started; the home screen consumes it.
     */
    val pendingImport = MutableStateFlow<PendingImport?>(null)

    /**
     * `true` while one of the app's activities is visible (from
     * `ProcessLifecycleOwner`, set in [ConvoApp]). The floating
     * recording control hides then (issue #7).
     */
    val appInForeground = MutableStateFlow(false)

    /**
     * `true` when the floating control or the Quick Settings tile asked
     * MainActivity to show the Record screen; the navigation graph
     * consumes it.
     */
    val pendingOpenRecord = MutableStateFlow(false)

    /**
     * Use case that records a voice sample and enrolls it as a
     * named speaker, so a fresh recording can label matched
     * voices with the user's name in the diarized transcript
     * (TASK-32 will surface this on the enrollment screen, and
     * TASK-22 will thread the embeddings into
     * [com.convoscribe.diarization.SpeakerMatcher]). Wired to
     * the sherpa-backed [speakerEmbedderFactory] today; the
     * mic source is the same one [recordingController] uses so
     * the enrollment audio matches the recorder's quality and
     * gain.
     */
    val enrollSpeakerUseCase = EnrollSpeakerUseCase(
        audioSource = MicAudioSource(),
        embedderFactory = speakerEmbedderFactory,
        repo = enrolledSpeakers
    )

    /**
     * Use case that promotes a session's diarized speaker to a
     * named, enrolled speaker without a separate recording
     * step: it slices the speaker's audio out of the session's
     * WAV, embeds it through [speakerEmbedderFactory] and
     * saves the vector through [enrolledSpeakers] (TASK-34).
     * The same session row is renamed in the same call so the
     * transcript reflects the new label without a separate
     * "Rename" tap. Wired here so production keeps the
     * embedder model resident for the duration of one
     * promotion but never across multiple sessions.
     */
    val enrollFromSessionUseCase = com.convoscribe.diarization.EnrollFromSessionUseCase(
        repository = sessionRepository,
        embedderFactory = speakerEmbedderFactory,
        store = enrolledSpeakers
    )

    /**
     * Dedicated OkHttp client for the LLM streaming endpoint. The
     * shared [httpClient] is sized for model downloads (120 s read
     * timeout); an LLM stream can run for several minutes, so the
     * LLM client gets its own client with a five-minute read
     * timeout. Kept separate from [httpClient] so a slow model
     * download never starves the chat thread of sockets.
     */
    val llmHttpClient: OkHttpClient = httpClient.newBuilder()
        .readTimeout(300, TimeUnit.SECONDS)
        .build()

    /**
     * Talks to the user's OpenAI-compatible endpoint. Settings are
     * read lazily through a lambda so a fresh base URL / API key /
     * model from the Settings screen is picked up on the next
     * streaming call without rebuilding the client. The lambda
     * reads both [SettingsRepository.settings] (a `StateFlow`) and
     * [SettingsRepository.apiKey] (an encrypted store), so the
     * session-summary / Q&A screens (TASK-24+) can use the same
     * instance for the lifetime of the app.
     */
    val chatClient: ChatClient = OpenAiChatClient(llmHttpClient) {
        val s = settingsRepository.settings.value
        LlmConfig(
            baseUrl = s.llmBaseUrl,
            apiKey = settingsRepository.apiKey(),
            model = s.llmModel
        )
    }

    /**
     * The installed version, from the package manager (`buildConfig`
     * is off). Robolectric has no versionName; the update check then
     * finds nothing to compare against and offers nothing.
     */
    val installedApp: InstalledApp by lazy {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        InstalledApp(
            versionName = info?.versionName.orEmpty(),
            versionCode = info?.longVersionCode ?: 0L
        )
    }

    /** Update APKs in `filesDir/updates/<version>/`; also cleaned up after an update. */
    val updateDownloads = UpdateDownloader(
        client = httpClient,
        dir = File(context.filesDir, "updates"),
        awaitNetwork = NetworkWaiter(context)::awaitNetwork
    )

    /**
     * Looks for a newer GitHub release on start and from Settings ›
     * About, and downloads it to `filesDir/updates/<version>/` with the
     * model downloader's resume and retries (issue #15). Never checks
     * automatically while a session is open or an import /
     * re-transcription runs.
     */
    val updateManager = UpdateManager(
        source = GitHubReleases(httpClient, userAgent = "ConvoScribe/${installedApp.versionName}"),
        store = PrefsUpdateStore(context.getSharedPreferences(PrefsUpdateStore.PREFS_NAME, Context.MODE_PRIVATE)),
        downloads = updateDownloads,
        inspector = PackageApkInspector(context),
        installer = PackageUpdateInstaller(context),
        installed = { installedApp },
        autoCheck = { settingsRepository.settings.value.updateCheckAuto },
        includePrereleases = { settingsRepository.settings.value.updateIncludePrereleases },
        isBusy = {
            recordingController.state.value.phase != RecordingPhase.IDLE ||
                importAudioUseCase.isRunning ||
                retranscribeUseCase.isRunning
        },
        scope = applicationScope
    )
}
