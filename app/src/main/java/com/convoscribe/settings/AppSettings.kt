package com.convoscribe.settings

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
 * [clusterThreshold] defaults to the value chosen with
 * `scripts/eval/eval_speakers.py --bench` (see its README).
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
    val numSpeakers: Int = -1,
    val clusterThreshold: Float = SettingsRepository.DEFAULT_CLUSTER_THRESHOLD,
    val speakerMatchThreshold: Float = 0.5f,
    val defaultParticipants: List<String>? = null,
    val displayMode: String = DisplayMode.AUTO.key,
    val dynamicColor: Boolean = false
)
