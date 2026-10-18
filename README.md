# ConvoScribe

ConvoScribe is an Android app for the Qualcomm Dragonwing Q-6690 that records conversations,
transcribes them on-device with sherpa-onnx (the NPU path uses the Nemotron Speech Streaming EN
0.6B model and the Hexagon NPU via QNN; the CPU path uses the Kroko streaming zipformer), labels
speakers with on-device diarization, and forwards the transcript to a user-configured
OpenAI-compatible endpoint for summaries and questions about the session.

## Features

- **Live, on-device speech-to-text** while recording. There is no
  automatic fallback between engines: until you pick one in Settings,
  the app uses the CPU, and switches to the NPU once Engine check has
  seen it work on the device.
  - **CPU** (default) — Kroko streaming zipformer, English and French.
  - **NPU** — Nemotron Speech Streaming EN 0.6B on the
    Hexagon DSP via sherpa-onnx's QNN backend, English only. Some
    Qualcomm devices have no usable NPU (the Boox Note Air 6C ships with
    it disabled). Engine check runs the NPU in a separate process, so a
    failure there is reported instead of closing the app.
- **Engine check** screen that runs an end-to-end pass on a bundled
  sample wave and reports the device, free storage, first-run QNN
  preparation time, real-time factor (RTF) and recognised text for
  whichever engine is requested. Reports can be copied / shared so
  you can attach them to a bug report.
- **On-device speaker diarization** after a recording stops. Each
  transcript line is labelled `Speaker 1`, `Speaker 2`, …, and a line
  that contains a speaker change is split there, using the recognizer's
  per-word timings. Rename a speaker (e.g. `Speaker 1` → `Alice`), or use
  *Re-identify speakers…* in the session menu to re-run it with a fixed
  number of people (2, 3, 4 or automatic). The count chosen before
  recording, or here, is kept with the session, and names you typed
  are kept.
- **Known voices.** Enroll people in Settings → Speakers, or use
  *Remember this voice* when renaming a speaker. Each enrollment adds a
  voice sample (up to 5 per person); new recordings label matching
  speakers by name.
- **Per-conversation options.** Before tapping Start, pick the
  language (English / Français), the number of speakers, the expected
  participants and an optional title. They start from the Settings
  defaults and are kept with the session; *Set as default* saves them
  as the new defaults. A language whose model is missing is downloaded
  first.
- **Expected participants.** Known voices are only matched against the
  people expected in the conversation (or anyone). Edit them later from
  the session menu (*Participants…*), which offers to re-identify
  speakers.
- **Session info** shows the language, speaker count, participants and
  the engine and model that produced the transcript.
- **Re-transcribe in another language** from the session menu: the
  stored audio is transcribed again in the background, with progress
  and Cancel, and speakers are re-identified. Cancelling or a failure
  keeps the previous transcript.
- **Import an audio file** as a session: *Import audio…* on Home, or
  share a voice note to ConvoScribe / *Open with* it from a file
  manager. Any format Android can decode (MP3, M4A/AAC, OGG/Opus,
  FLAC, AMR, WAV…) is converted to 16 kHz mono, stored as the
  session's recording and transcribed in the Settings language, then
  speakers are identified. It runs in the background with progress and
  Cancel; a cancelled or unreadable import leaves nothing behind.
- **Continue a recording** from its session screen (mic button). The
  audio and transcript are appended to the same session, in its own
  language.
- **Foreground microphone service** keeps recording alive while the
  screen is off or the app is in the background, with a
  Stop action on the persistent notification.
- **Crash-safe sessions** — sessions persist across restarts in a
  Room database; a recording interrupted by an `am force-stop` is
  recovered on next launch and lands in the session list with
  whatever transcript was already transcribed.
- **Summaries and Q&A** through any
  OpenAI-compatible `/v1/chat/completions` endpoint (vLLM,
  llama.cpp server, Ollama with `http://<host>:11434/v1`, OpenAI,
  …); answers stream token-by-token and are saved with the session.
- **English and French** — pick Français for a conversation (or as
  the default in Settings) to download the French model (~71 MB) and
  transcribe French speech with the CPU engine. Settings shows each
  language's model status ("installed" / "missing").
- **Play back a session's recording** from its session screen: play /
  pause, a seek bar, ±10 s skip and 1× / 1.5× / 2× speed. Tap a
  transcript line to play from there; while playing, the current line
  is highlighted (and the current word, for lines recorded with word
  timings) and the list follows along until you scroll by hand — tap
  *Follow* or seek to resume. Playback stops when you leave the screen.
- **Share or save the audio** from the session menu (*Share audio*,
  *Save audio…*) as an M4A (AAC, about 0.5 MB a minute), encoded on
  demand with a progress dialog you can cancel.
- **Share transcript** to any system share-sheet target (notes app,
  email, …) and rename sessions / speakers from the overflow menu.
- **Designed for phones and e-ink.** A teal design system (colours,
  type, shapes and spacing in `ui/theme/`) with light and dark
  themes, and an **E-ink** display mode — black on white, flat
  outlined surfaces, no animations or ripples, larger touch targets
  and text. Settings → Appearance → *Display*: **Auto** (E-ink on an
  Onyx Boox, Standard elsewhere), **Standard** or **E-ink**. On
  Android 12+ Standard mode can take its colours from the wallpaper
  (*Use device colours*).
- **Session cards** on Home show the date, duration, number of
  speakers and whether a session is recording, identifying speakers
  or failed, with Delete in each card's menu. The Record screen shows
  a live **microphone level** meter, and each speaker keeps one colour
  on their chip and lines, also after a rename.

## Build

```bash
bash scripts/setup-toolchain.sh   # one-time, installs JDK, SDK, NDK and builds the sherpa-onnx native libs
source scripts/env.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

### Screenshots

`ScreenshotTest` renders every screen with fake data (phone light,
dark and 200 % font, and the Boox in E-ink mode). A normal test run
only composes the screens; to write PNGs to
`app/build/outputs/roborazzi/`:

```bash
./gradlew testDebugUnitTest --tests '*ScreenshotTest*' -Proborazzi.test.record=true
```

## Install

Run `bash scripts/build-apk.sh` to build and copy the APK to `dist/convoscribe-<versionName>-debug.apk` (the wrapper prints the exact path). Add `--install` to also install it on the connected device once the build succeeds (set `ANDROID_SERIAL` to pick one of several).

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`usesCleartextTraffic="true"` is already enabled on `<application>`
so plain-HTTP local endpoints (e.g. `http://192.168.1.10:8000/v1`,
Ollama at `http://<host>:11434/v1`) work without a custom
`network_security_config`. Do not place a stricter network
security config on the app — that would block the local endpoint.

## Boox / Onyx e-readers

Tested on a Boox Note Air 6C (Qualcomm QCS6690, Android 16):

- **No NPU.** The chip's NPU is disabled in hardware (the kernel log says
  `nsp part defective` and there is no `cdsp` remote processor), so use the
  CPU engine, which is the default until Engine check sees the NPU work.
  Engine check's "Check NPU" reports the failure without closing the app.
- **The CPU engine is fast enough.** Check CPU reports a real-time factor
  of about 0.03 (3.8 s of audio in 0.12 s with 4 threads).
- **Screen-off recording works.** Onyx refuses the recording wake lock
  (`WakeLock tag:ConvoScribe:recording … is forbidden` in logcat), but the
  foreground microphone service keeps the app running. In a 10-minute test
  with the screen off and the device dozing, the recording captured 610.7 s
  of audio in 611 s, with no gaps, while Android froze or killed other
  background apps. No settings change was needed.
- If recordings ever stop with the screen off on your device, exempt
  ConvoScribe from Onyx's app freezing and background restrictions in the
  Boox app-management settings, then test again.

## Manual testing

The agent loop cannot run on-device tests (the sherpa-onnx native
library only loads on real hardware). The owner-side checklist
that validates the PRD acceptance criteria is at
[`docs/MANUAL_TEST.md`](docs/MANUAL_TEST.md) — numbered checks
covering install, model download, both engines, recording,
diarization, renaming, sharing, the LLM endpoint, summaries, Q&A,
French, deletion, crash recovery, performance, engine switching,
per-conversation options and the display modes. A troubleshooting section shows how to read
`adb logcat`, where models live on disk, and how to clear app
data.

## Models

| bundleId | engine | bytes (sum of declared files) | what |
|---|---|---|---|
| `asr-en-nemotron-0.6b-560ms-qnn` | NPU | 609 181 848 (≈581 MB after the `.tar.bz2` is unpacked) | English speech recognition. Source archive `…-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2` (421 938 876 compressed bytes). Provider `qnn`, `modelType = "nemo_transducer"`. `encoder.bin`/`decoder.bin`/`joiner.bin` QNN context binaries are produced on first run and cached. |
| `asr-en-kroko-2025-08-06` | CPU | 71 237 822 (≈68 MB) | English speech recognition. Streaming zipformer (`encoder.onnx`, `decoder.onnx`, `joiner.onnx`, `tokens.txt`, `test_wavs/0.wav`). `modelType = "zipformer2"`. |
| `asr-fr-kroko-2025-08-06` | CPU | 71 056 155 (≈68 MB) | French speech recognition. Same file layout as English without the test wave. `modelType = "zipformer2"`. |
| `seg-pyannote-3-0-int8` | CPU (always) | 1 540 506 (≈1.5 MB) | Speaker segmentation. `model.int8.onnx`. |
| `emb-wespeaker-resnet34-lm` | CPU (always) | 26 530 550 (≈25 MB) | Speaker embeddings. `wespeaker_en_voxceleb_resnet34_LM.onnx`. |

Sources:

- NPU archive:
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-2/sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2`
- English ASR: `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/<file>`
- French ASR: `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/<file>`
- Segmentation: `https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx`
- Embeddings: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx`
  (the `recongition` misspelling is in the upstream tag and must be preserved).

Models are downloaded on first run into
`context.filesDir/models/<bundleId>/` — see the troubleshooting
section of `docs/MANUAL_TEST.md` for the exact path and how to
inspect / clear it with `adb shell`.

## Architecture

Single-module Android app `:app`, Kotlin + Jetpack Compose,
Material 3. Source under `app/src/main/java/com/convoscribe/`,
one package per concern:

- `audio/` — PCM16 16 kHz primitives: `WavWriter`, `WavReader`,
  `MicAudioSource` (a thin `AudioRecord` wrapper).
- `models/` — `ModelCatalog` (the bundle table above),
  `ModelDownloader` (HTTPS + tar.bz2 stream), `ModelStore` (the
  on-disk layout and per-file presence check).
- `asr/` — engine-agnostic streaming interface:
  `StreamingTranscriber`, `TranscriberFactory`,
  `TranscriptEvent` (Partial / Final), `EndpointSegmenter`,
  `TextNormalizer`.
- `diarization/` — post-record diarization pipeline:
  `DiarizeSessionUseCase` (single rule, called from the
  foreground service after `controller.stop()` and from the
  crash-recovery boot pass), `Diarizer`, `SpeakerTurn`,
  `SpeakerAssigner`.
- `engine/sherpa/` — the only package that ever imports
  `com.k2fsa.sherpa.onnx`: `SherpaRecognizerBackend`,
  `SherpaTranscriberFactory`, `SherpaDiarizer`,
  `SherpaDiarizerFactory`, `SherpaInfo`, `SherpaAsrConfigs`,
  `QnnRuntime` (`prependAdspLibraryPath`).
- `data/` — Room database (`AppDatabase`), entities and DAOs for
  sessions, segments, speakers, chat messages; `SessionRepository`
  is the single façade the rest of the app reads / writes through.
- `settings/` — `SettingsRepository` (SharedPreferences),
  `AppSettings`, `SecretStore` (EncryptedSharedPreferences) for
  the LLM API key.
- `llm/` — `ChatClient` (OkHttp + kotlinx.serialization, SSE
  streaming), `LlmException`, `LlmMessage`, `PromptBuilder`,
  `SseParser`, `TranscriptTruncator`.
- `recording/` — `RecordingController` (the audio + ASR loop),
  `RecordingState`, `RecordingStore`, `SessionRecovery` (the
  crash-recovery boot pass).
- `service/` — `RecordingService` (foreground microphone service),
  notification text helper.
- `enginecheck/` — `DeviceInfo` (manufacturer / SoC / libcdsprpc /
  storage), `EngineCheckRunner`, `Report`.
- `ui/<screen>/` — one package per screen, Composable + ViewModel
  pair (`home`, `record`, `session`, `settings`, `setup`,
  `enginecheck`); `ui/AppNavHost.kt` is the navigation graph;
  `ui/theme/` is the Material 3 theme.

Dependency injection is manual: `com.convoscribe.AppContainer` is
built in `com.convoscribe.ConvoApp : Application` and is the
single source of every singleton. ViewModels are constructed with
a `viewModelFactory { initializer { … } }` that reads from the
container.

## LLM endpoint

The LLM endpoint is configured in **Settings → Language model**:

- **Base URL** — the OpenAI-compatible base URL. Must end with
  `/v1`. Examples:
  - OpenAI: `https://api.openai.com/v1`
  - vLLM / llama.cpp server: `http://<host>:8000/v1`
  - Ollama: `http://<host>:11434/v1` (Ollama exposes an
    OpenAI-compatible shim on `/v1`)
- **API key** — optional, stored in `EncryptedSharedPreferences`.
- **Model name** — free text, e.g. `meta-llama-3.1-8b-instruct`.
  Tap **Test connection** to populate the chip list with model ids
  returned by the endpoint.
- **Max transcript characters** — the context-window cap for
  summaries and Q&A; the default 24 000 chars fits several hours of
  conversational text.

The endpoint is contacted only by `llm/ChatClient.kt`. The
streaming path uses server-sent events (`data: {…}\n\n` chunks)
and surfaces partial tokens to the UI as soon as the SSE parser
sees the `delta.content` field.

## Model licences

Every model bundled by the on-device ASR / diarization pipeline
is used under its upstream licence; Settings → About shows the
same list with each model name + licence + source URL.

- **Nemotron Speech Streaming EN 0.6B** — NVIDIA Open Model
  License, https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/
- **Kroko streaming EN / FR** — CC-BY-SA 4.0
  (Banafo Kroko ASR community models,
  https://huggingface.co/Banafo/Kroko-ASR). Attribution is shown
  in this README and in Settings → About.
- **pyannote segmentation 3.0** — MIT.
- **WeSpeaker ResNet34-LM** — Apache-2.0.
- **sherpa-onnx** — Apache-2.0, https://github.com/k2-fsa/sherpa-onnx.
- **Qualcomm AI Engine Direct (QNN) runtime libraries**
  (`libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`,
  `libQnnHtpV{68,69,73,75,79,81}{Skel,Stub}.so`) — redistributed
  unmodified under the Qualcomm licence by sherpa-onnx; see
  https://developer.qualcomm.com/software/qualcomm-neural-processing-sdk.

The bundled QualComm QNN runtime libraries ship unmodified so the
NPU backend (Hexagon DSP via `libcdsprpc.so`) can be reached at
runtime. All of `app/src/main/jniLibs/` and `app/libs/*.jar` are
git-ignored build artifacts.

## Native engine (sherpa-onnx + Qualcomm QNN)

The on-device ASR and diarization engine is
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) pinned to
**v1.13.8**. The published Android AAR does not include the Qualcomm QNN
backend, and the prebuilt QNN demo APKs ship without speaker
diarization, so we compile the native library from source with both
features enabled.

`scripts/build-sherpa-onnx.sh` (called automatically at the end of
`scripts/setup-toolchain.sh`):

1. Shallow-clones the `v1.13.8` tag of `k2-fsa/sherpa-onnx` into
   `~/.local/share/convoscribe-toolchain/sherpa-onnx-build/`.
2. Downloads the **Qualcomm QNN 2.40.0.251030** C headers and runtime
   shared libraries from the
   [sherpa-onnx QNN assets release](https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models-qnn).
3. Runs the upstream `build-android-arm64-v8a.sh` with
   `SHERPA_ONNX_ENABLE_QNN=ON`, `SHERPA_ONNX_ENABLE_SPEAKER_DIARIZATION=ON`,
   `SHERPA_ONNX_ENABLE_TTS=OFF`, `SHERPA_ONNX_ENABLE_BINARY=OFF`,
   `SHERPA_ONNX_ENABLE_C_API=OFF`, `SHERPA_ONNX_ENABLE_JNI=ON` and
   `BUILD_SHARED_LIBS=ON`. This fetches a prebuilt onnxruntime
   (`csukuangfj/onnxruntime-libs`) and links it as a shared library.
4. Copies `libsherpa-onnx-jni.so` + `libonnxruntime.so` from
   `build-android-arm64-v8a/install/lib/` and the QNN runtime
   (`libQnnHtp.so`, `libQnnHtpPrepare.so`, `libQnnSystem.so`,
   `libQnnHtpV{68,69,73,75,79,81}{Skel,Stub}.so`) into
   `app/src/main/jniLibs/arm64-v8a/`.
5. Verifies the resulting `libsherpa-onnx-jni.so` exports both
   `Java_com_k2fsa_sherpa_onnx_OfflineSpeakerDiarization` and the
   `prependAdspLibraryPath` symbol (QNN path).
6. Downloads `sherpa-onnx-1.13.8.aar`, checks its sha256 against
   `633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96`,
   and extracts the AAR's `classes.jar` into
   `app/libs/sherpa-onnx-classes-1.13.8.jar` (the AAR itself is **not**
   used as a dependency because its bundled native libs have no NPU).

The build takes **10-30 minutes** on a fresh checkout. Re-runs are
idempotent: a second `bash scripts/build-sherpa-onnx.sh` prints
`sherpa-onnx native libs OK` and exits 0. Pass `--force` to wipe
`$WORK` and rebuild from scratch.

## Test

```bash
source scripts/env.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
```

To measure speaker attribution on real recordings, and to tune the
clustering threshold, use the desktop evaluation tool in
[`scripts/eval/`](scripts/eval/README.md). It runs the app's models and
settings with sherpa-onnx's Python package and scores hand-labelled
recordings. The default clustering threshold, 0.75, was chosen with its
benchmark of AMI meetings and synthetic conversations; the old default,
0.5, found 2–4 times too many speakers.

The `testDebugUnitTest` phase runs JUnit 4 + Robolectric +
Turbine in the JVM; sherpa-onnx classes are never instantiated in
unit tests (their native library cannot load on the JVM), only the
`*Config` data classes are touched. The full suite runs in ~17 s
with `unitTests.maxParallelForks = 1`.
