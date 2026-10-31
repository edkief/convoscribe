# ConvoScribe

ConvoScribe is an Android app for the Qualcomm Dragonwing Q-6690 that records conversations,
transcribes them on-device with sherpa-onnx (the NPU path uses the Nemotron Speech Streaming EN
0.6B model and the Hexagon NPU via QNN; the CPU path uses the Kroko streaming zipformer), labels
speakers with on-device diarization, and forwards the transcript to a user-configured
OpenAI-compatible endpoint for summaries and questions about the session. Sessions stay on the
device unless you turn on sync, which keeps them on your own self-hosted sync server and
brings them to your other devices.

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
- **Identify speakers when it suits you.** Settings → Speakers →
  *Identify speakers*: **Auto** (straight after the transcript, the
  default), **Charging** (at once if plugged in, otherwise the next time
  the device charges, even with the app closed) or **On demand**. A
  session that waits shows *Speakers pending*, and *Identify speakers
  now* on the session screen runs it whatever the setting. The setting
  covers Stop, import and re-transcription. Unplugging stops the run
  and the session waits for the next charge. A session interrupted by
  a crash also waits. Android 14+ gives a background job about 10
  minutes, so a very long session may not finish in the background; a
  notification then asks you to start it from the app. **Cancel** on
  the *Identifying speakers* notification or on the session screen
  stops a run in progress, whoever started it: the session goes back
  to *Speakers pending* with its transcript intact. Cancelled while
  charging, nothing is identified automatically until the charger is
  unplugged and plugged in again; *Identify speakers now* still works.
- **Speed up speaker identification** (to compare, issue #56).
  Settings → Speakers → *Voice model*: **ResNet34** (default) or
  **CAM++**, a lighter model (29 MB download, offered there). Known
  voices are always matched with ResNet34, so enrollments keep working.
  *Analysis step*: how far the 10 s analysis window moves each time,
  **1 s** (default), 2, 3 or 5 s; a longer step does less work. Session
  info shows the model, step and time of each session's last
  identification, with its real-time factor.
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
- **Transcribe on your own server (optional).** Point Settings →
  *Transcription server* at a self-hosted, OpenAI-compatible
  transcription server, such as NVIDIA Parakeet TDT 0.6B v3 on a GPU
  (see [Transcription server](#transcription-server)). *Re-transcribe*
  then offers *On the transcription server*, and with *Improve
  recordings on the server* on, every stopped recording and every
  import is transcribed there before speakers are identified. Live
  transcription while recording always stays on the device; if the
  server cannot be reached, the device transcript is kept. A transcript
  you edited by hand is never replaced automatically. Session info shows
  `server` and the server's model.
- **Import an audio file** as a session: *Import audio…* on Home, or
  share a voice note to ConvoScribe / *Open with* it from a file
  manager. Any format Android can decode (MP3, M4A/AAC, OGG/Opus,
  FLAC, AMR, WAV…) is converted to 16 kHz mono, stored as the
  session's recording and transcribed in the Settings language (on the
  transcription server when *Improve recordings on the server* is on),
  then speakers are identified. It runs in the background with progress and
  Cancel; a cancelled or unreadable import leaves nothing behind.
- **Search across sessions** from Home (the search icon, or the
  search field on wide screens). Words are matched in session titles,
  transcript lines, speaker names and saved summaries, ignoring capitals
  and accents (*reunion* finds *Réunion*), each word as the start of a
  word. Results are grouped per session with the matching words
  highlighted and each line's time; tapping a line opens the transcript
  there with the player positioned on it (not playing), and a summary
  hit opens the Summary tab. Filter by language, date (last 7 / 30
  days, this year) and person. On e-ink the search waits for a longer
  pause in typing, so the screen refreshes once per burst. With the
  semantic search model installed (below), Search also finds what was
  said in other words or in the other language.
- **Edit the transcript.** Long-press a line (or use TalkBack's
  actions on it) for *Edit text*, *Change speaker* (to an existing
  speaker or a new one), *Merge with next*, *Split here* (at the
  cursor, snapped to a word) and *Delete line*; a snackbar offers
  *Undo* for the latest edit. Words you did not change keep their
  timings, so playback still highlights the word being said. Edited
  lines are marked *edited*. *Re-identify speakers* keeps the speakers
  you picked by hand and never splits a line whose text you corrected;
  *Re-transcribe* asks before replacing an edited transcript. Summary,
  Q&A, search and *Share* use the edited text. Editing is off while the
  session is recording, identifying speakers or being re-transcribed.
- **Continue a recording** from its session screen (mic button). The
  audio and transcript are appended to the same session, in its own
  language.
- **Foreground microphone service** keeps recording alive while the
  screen is off or the app is in the background, with Mark, Pause /
  Resume and Stop actions on the persistent notification.
- **Pause and resume** a recording (Record screen, notification,
  floating control or tile) without ending the session: the microphone
  is released, nothing is recorded or transcribed while paused, and
  the session is only diarized once you stop. The timer and the
  transcript times follow the recorded audio, so they stay in step
  with playback.
- **Mark moments** while recording, so the parts that matter are easy
  to find again: one tap on *Mark* on the Record screen, the
  notification (which then shows the count) or the floating control
  (every shape) marks the current point of the recording, also while
  paused (it marks the paused position). On the Record screen you can
  add a short note to the mark you just took; it is never required. In
  the session, each mark is a flag above the line being spoken at the
  time, with its note, and a tick on the seek bar; tap a flag to jump
  there. *Marks (n)* in the player (or *Marks* in the menu) lists them
  to jump to, edit the note of or delete. Summaries are asked to give
  marked moments priority, and the shared transcript includes them.
- **Notes from marks.** Once a session's speakers are identified, the
  language model turns each mark's short note into a full note, written
  from what was said in the minute before the mark and the half-minute
  after it, with speaker names (a mark without a note gets a note on
  what was being discussed). The notes are listed under the summary on
  the Summary tab: tap a mark's time to open the transcript there, and
  *Write notes* / *Regenerate notes* writes them again at any time,
  with progress and Stop. They are written one at a time and go on if
  you leave the session. Re-identifying speakers, renaming a speaker or
  re-transcribing marks a note *Outdated*; editing a mark's note removes
  its full note. The *Marks* list shows the start of each note and the
  shared transcript includes them. Turn the automatic notes off in
  Settings → Summaries (*Write notes for marks automatically*). Nothing
  is sent without an endpoint, and an unreachable one leaves the notes
  unwritten without an error.
- **Record from anywhere.** While a session is open and you switch to
  another app, a small **floating control** shows *Recording* or
  *Paused*, the time, Mark, Pause / Resume and Stop; drag it anywhere (it
  rests against the nearest side edge and remembers where), tap it to
  open the Record screen. It comes in three shapes, chosen in
  Settings → Recording or with the arrow on the control: **Minimal**
  (status dot, Mark, Pause / Resume, Stop; nothing on it changes by
  itself, and Mark answers with a vibration only, so an e-ink screen is
  never refreshed between taps), **Standard**
  (as above) and **Expanded** (also the last sentences transcribed;
  on e-ink it updates once per sentence, not per word). Minimal is the
  default on e-ink, Standard elsewhere. It needs Android's *Display over other apps*
  permission, which the app explains once (the first time you start a
  recording, or when you turn it on in Settings → Recording); without
  it, the notification is the control. A **Quick Settings tile**
  (*ConvoScribe*) starts a new recording with the Settings defaults
  from anywhere, and pauses or resumes an open one; it shows whether a
  session is recording or paused. Long-press it to start with a
  preset instead (see *Organise conversations*).
- **Crash-safe sessions** — sessions persist across restarts in a
  Room database; a recording interrupted by an `am force-stop` (also
  while paused) is recovered on next launch and lands in the session list with
  whatever transcript was already transcribed.
- **Summaries and Q&A** through any
  OpenAI-compatible `/v1/chat/completions` endpoint (vLLM,
  llama.cpp server, Ollama with `http://<host>:11434/v1`, OpenAI,
  …); answers stream token-by-token and are saved with the session.
  Summaries, notes and answers are shown formatted (headings, bold,
  lists) from the model's Markdown; Copy and share keep the Markdown.
  While a question waits for its first words, the Ask tab shows pulsing
  dots (still dots and *Generating answer…* on e-ink); on e-ink the
  answer is then redrawn once a second rather than word by word.
  A conversation longer than *Max transcript characters* is still
  covered in full: the summary is written in parts (notes on each part
  of the transcript, then the summary from the notes, with *Summarising
  part 2 of 5…* while it runs), and a question is answered from the
  parts of the transcript most relevant to it, picked on the device.
  Such answers say what they were based on (*Based on 18 of 92
  minutes…*).
- **Ask across sessions** — ask a question about all your past
  conversations (*What did Alice say about the budget last month?*)
  from the speech-bubble icon on Home or on the Search screen. Narrow it
  with the Search filters (person, language, date, tag). The sessions
  sharing the most words with the question are picked from the search
  index, their most relevant parts and saved summaries are sent to the
  language model, and the answer cites each session it used as chips
  (*Title · date · mm:ss*) that open the transcript at that line. Each
  answer says what it was based on (*Based on 7 excerpts from 4
  sessions*). The history is kept until *Clear history*; deleting a
  session keeps the answers that cite it, marked *Session deleted*.
  Matching is by words (ignoring case and accents), so a question
  worded differently from what was said may miss it, unless semantic
  search is installed.
- **Semantic search** — an optional on-device model (Google's
  EmbeddingGemma, about 202 MB, Gemma Terms of Use), downloaded from
  Settings → Semantic search, lets *Ask across sessions* also find what
  was said in other words or in the other language (*how much can we
  spend* finds *the budget is capped at 10k*, and English questions
  find French sessions). Each session is cut into passages of about
  1,200 characters, and the passages are turned into vectors in the
  background, even with the app closed: by default while the device is
  charging, like *Identify speakers*, or, with Settings → Semantic
  search → *Index sessions* → **Any time**, whenever the battery is not
  low. **Index now** in that section indexes every waiting session
  straight away, and *Index for semantic search* in a session's menu
  indexes just that one. A session whose
  transcript, speakers or summary change is indexed again. A question
  then picks sessions and excerpts by meaning as well as by words, and
  the answer says *matched by meaning*. Sessions not indexed yet still
  count by their words, and the screen says how many there are. Search
  uses the same passages: a query also finds sessions close to it in
  meaning (*money for the trip* finds *the holiday budget is 2,000
  euros*), within the same filters, and such hits are marked *Matched
  by meaning* with nothing highlighted; a line opens the transcript at
  the start of the passage that matched. Word matches show at once and
  the meaning matches join them a moment later. A query of one or two
  words keeps its word matches first, in the usual order, with the
  meaning matches after them; a longer query is ranked by both, so a
  natural question (*when did we talk about money for the trip?*) puts
  the closest sessions first. Without the model, or if it fails, Ask
  across sessions and Search match words only, as before. Settings shows how many sessions are indexed, and deleting the
  model drops the index. Engine check → *Semantic search* times the
  model and shows the app's memory use, to judge it on a CPU-only
  device.
- **Sync to your own server** (optional, off by default) — Settings →
  *Sync* keeps every finished session (recording, cleaned recording,
  transcript with word timings, speakers, summaries and questions,
  marks and tags) on the self-hosted [sync server](#sync-server) and
  on all your devices, in the background whenever a network is
  available (and when the app opens), never while recording. Record on
  the phone, then read, search and ask on the Boox: sessions from other
  devices arrive with their transcript first, and their recording
  follows on Wi-Fi (or on mobile data with *Download recordings on
  mobile data*); until then the session says *Audio downloading*.
  Edits made on any device reach the others. When the same session
  changed on two devices, the **longer recording wins**, then the
  **most recently changed**, and every device ends on that version; a
  delete wins over an edit unless the edited version has a longer
  recording. **Delete** removes a session from every device; **Remove
  from this device** (in a session's menu) frees space here and keeps
  it on the server, where it shows greyed as *On server* in the list
  until you tap *Download*. Recordings move in parts of about 8 MB, so
  an interrupted upload or download resumes where it stopped and never
  leaves half a session. Turning sync on asks first, since recordings
  and transcripts leave the device; API keys are never uploaded.
  Settings shows when it last synced, how many sessions are waiting to
  upload or download and the last error, with **Sync now**. With sync
  off, no server or no network, everything else works as before.
  Known voices, presets and your summary templates sync too (#70):
  adding, editing or deleting one on a device does the same on the
  others, the most recent edit wins, and a preset keeps its tags and
  template by name. Items both devices already had (say after
  restoring a backup) merge into one on the first sync. A session's
  semantic-search vectors travel with it, so a device that has the
  same embedding model and the same text uses them instead of indexing
  the session again; otherwise it indexes the session itself, as
  before.
- **Action items as a checklist** (issue #31). A summary's action
  items become a checklist on the Summary tab, below the summary. The
  model lists them again as JSON after the summary (the bullet list is
  read when that is missing or broken), with who will do each one
  (matched to the speakers and participants), a due date when one was
  said, and the moment it was agreed. Tick an item off, edit its text,
  owner and due date, or delete it. Tap its time to go to that moment
  in the transcript. Its menu also shares it as text or adds it to the
  calendar (an all-day event on its due date). Regenerating keeps what
  you ticked, edited or deleted and adds only new items. Home's menu →
  *Open actions* lists every session's unticked items, by session or
  by owner. Items are backed up and synced with their session.
  Summaries saved before this version get items when regenerated.
- **Summary templates** — pick how a summary is laid out: *Meeting*
  (the default: overview, key points, decisions, action items),
  *Interview*, *Lecture / talk notes*, *1:1*, *Client / patient visit*
  or *Brief* (3–5 bullets). Choose one for each conversation before
  recording (with *Set as default*), or switch on the Summary tab and
  Regenerate. Each summary shows which template wrote it. Settings →
  Summaries → Templates adds, edits, duplicates and deletes your own
  templates; built-ins can be duplicated, not edited. Every template
  keeps the same rules: write in the transcript's language, keep
  speaker names, and don't invent facts.
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
- **Noise cleanup (optional).** For recordings made in noisy places,
  Settings → *Noise cleanup* downloads a small speech-enhancement model
  (GTCRN, about 0.5 MB) and can clean new recordings by default; the
  Record screen has a *Clean up noise* switch per conversation. Live
  cleaning writes a cleaned copy (`<id>.clean.wav`) next to the
  original, sample for sample, and the player switches between
  *Cleaned* and *Original*. A finished or imported session can be
  cleaned afterwards (*Clean up audio* in the menu; it transcribes again,
  keeping speaker names) and *Remove cleaning* deletes the copy. The
  recognizer hears the cleaned audio blended half and half with the
  original: fully cleaned audio sounds better but transcribes worse,
  and the blend keeps transcripts at least as good (see
  `scripts/eval/eval_cleanup.py`). Speakers are identified from the
  original. *Engine check* → *Check noise cleanup* says whether the
  device can clean while recording; otherwise clean afterwards.
- **Organise conversations.**
  - **Tags** group conversations by project, client or anything else.
    Add them on the Record screen (*Tags*) or from a conversation's menu
    (*Tags…*); type a new name or tick existing ones (matched ignoring
    case). Tags show on the session cards and under a conversation's
    header. Once conversations are tagged, a row of tag chips above the
    Home list narrows it to conversations with *any* ticked tag, and
    Search has a tag filter. Settings → Organise → Tags renames tags
    (onto another tag's name merges them) and deletes them; the
    conversations themselves are kept.
  - **Automatic titles.** After the first summary, a conversation that
    still has its date as its title (*Recording 2026-03-04 09:05*) is
    renamed by the language model to a short title in its language,
    with *Undo* on the snackbar and *Restore “…”* in the menu
    afterwards. A title you typed, a preset's title, an imported file's
    name or a rename is never changed. Turn it off in Settings →
    Summaries (*Name conversations automatically*).
  - **Presets** keep the options of a recurring conversation: language,
    speakers, participants, a title, tags, the summary template and
    noise cleanup. The title can hold `{date}` and `{time}`, filled in
    when recording starts (*Weekly sync {date}*). Save one with *Save
    as preset…* on the Record screen, pick it in *Preset* above the
    options, or manage them in Settings → Organise → Presets.
    Long-pressing the Quick Settings tile lists the presets and starts
    a recording with the one you pick (or opens the Record screen with
    it when the microphone permission or the models are missing).
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
- **In-app updates.** When you open the app (at most every 30
  minutes) and from a background job every hour while it is closed
  (never while a recording is open), the app asks GitHub for a newer release. One
  found in the background is announced by a notification. It is
  offered on Home, also after a restart: *View notes*, *Update*, *Not now* or *Skip this version*. The
  APK downloads in the background with a progress notification
  (resuming after a lost connection), is checked against the
  release's sha256 and against the installed app (same package,
  higher `versionCode`, same signing key), then goes to the system
  installer, which you confirm. Settings → About shows the version,
  *Check for updates*, and switches for the automatic check (on) and
  for pre-releases (off).
- **Back up and restore** (issue #25). Settings → Data → *Back up
  sessions…* saves one file, `convoscribe-backup-YYYYMMDD.zip`, wherever
  the system file picker allows (Downloads, a USB stick, a cloud
  drive). It holds every session (recording and cleaned copy,
  transcript with word timings, speakers and their names, summaries,
  questions and answers, marks and their notes, tags) plus known voices,
  summary templates, presets and settings. API keys are never written,
  nor device-specific settings (ASR engine, threads); a session still
  recording is left out. *Restore from backup…* adds the backup's
  sessions to this device; a session already here (matched by its
  stable id) is kept as it is, so restoring the same file twice changes
  nothing. *Also restore settings* (ticked) applies the backed-up
  settings once the restore finishes. Both run in the background with
  a progress notification and Cancel. Each session is restored whole
  or not at all: a cancelled or failed restore keeps the sessions it
  finished, and restoring again adds the rest. A backup from a newer
  version of the app, or a file that is not a backup, is refused
  before anything changes. Speakers are identified and sessions indexed
  for semantic search again by the usual jobs where needed. A backup is
  not encrypted: keep it somewhere private.

## Build

```bash
bash scripts/setup-toolchain.sh   # one-time, installs JDK, SDK, NDK, Node.js and builds the sherpa-onnx native libs
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

Later versions arrive in the app (Features → *In-app updates*): the
first install asks for Android's *Install unknown apps* access for
ConvoScribe, then every update shows the system prompt. Releases are
signed with the shared `app/debug.keystore` for now, so they install
over a debug build. Once releases get their own key (#16), a copy
signed with the old key cannot update in place: the app says so and
links to the release page, and you uninstall and reinstall once.
Uninstalling deletes the sessions, so first use Settings → Data →
*Back up sessions…* and save the file outside the app (e.g.
Downloads), then uninstall, install the new build and use *Restore
from backup…* (Features → *Back up and restore*). The same steps move
your sessions to a new device.

`usesCleartextTraffic="true"` is already enabled on `<application>`
so plain-HTTP local endpoints (e.g. `http://192.168.1.10:8000/v1`,
Ollama at `http://<host>:11434/v1`) work without a custom
`network_security_config`. Do not place a stricter network
security config on the app — that would block the local endpoint.

## Release

`scripts/release.sh` cuts a GitHub release in two halves. You run the
first; pushing the tag makes Tekton run the second.

1. **`prepare`** (locally) bumps `versionName` (minor by default;
   `--patch`, `--major` or `--version X.Y.Z`) and `versionCode` in
   `app/build.gradle.kts`, commits `chore(release): vX.Y.Z`, tags it
   `vX.Y.Z`, and pushes the branch and tag together. Releases come from
   `main` with a clean tree that is not behind `origin`.
2. **`publish`** (Tekton, on the `vX.Y.Z` tag push; see `.k8s-build.yaml`)
   asks an OpenAI-compatible LLM to write release notes from the git log
   since the previous `v*` tag, runs the verify gate and
   `assembleRelease`, and runs `gh release create` with
   `convoscribe-X.Y.Z.apk` and its `.sha256`. It does nothing if the
   release already exists, so re-running the pipeline is safe.

The release notes leave out commits that only touch `server/`. The sync
server releases on its own `server-vX.Y.Z` tags with
**`prepare-server`**, which works like `prepare` on the `version` in
`server/package.json`. Pushing the tag makes Tekton build the server
image; see [`server/README.md`](server/README.md#release).

Release builds are signed with the shared `app/debug.keystore` for now,
so they upgrade in place over debug installs (a dedicated release key is
#16).

```bash
bash scripts/release.sh prepare                    # 0.1.0 -> 0.2.0, push v0.2.0
bash scripts/release.sh prepare --patch --dry-run  # print the next version only
bash scripts/release.sh prepare-server             # server 0.1.0 -> 0.2.0, push server-v0.2.0
# publish runs in CI; locally, on a checkout of the tag:
LLM_API_KEY=... bash scripts/release.sh publish
LLM_API_KEY=... bash scripts/release.sh publish --dry-run   # notes and APK into dist/, any HEAD
bash scripts/release.sh publish --notes-file my-notes.md    # skip the LLM
```

The notes endpoint defaults to `http://llama-chat.llama:8080/v1`, model
`llama-chat` (`LLM_BASE_URL`, `LLM_MODEL`). The script turns the model's
thinking off with `chat_template_kwargs`. Set `LLM_DISABLE_THINKING=0`
for servers that reject that field. In an interactive shell both halves
ask before pushing or publishing (`--yes` skips the prompt).

If `publish` fails in CI after the tag is pushed (a failing test, say),
the tag stays without a release. Fix it and release the next patch with
`prepare --patch`. Alternatively, delete the tag
(`git push --delete origin vX.Y.Z && git tag -d vX.Y.Z`), revert the
version bump, and prepare again. For a transient failure (LLM down,
network), re-run the PipelineRun. To publish a tag again by hand, start
`container-build-pipeline` with `git-branch=refs/tags/vX.Y.Z` and
`git-revision` set to the tag's commit.

### sherpa-onnx is prebuilt for releases

The release never compiles sherpa-onnx. If its outputs are missing from
`app/`, it copies them from `$CONVOSCRIBE_SHERPA_PREBUILT` or extracts
them from the sherpa image with podman or docker. Two images
(Containerfiles in `docker/`, build context the repo root) make this
work in CI:

- `convoscribe-sherpa:<sherpa>-qnn<qnn>-ort<onnxruntime>-jni<tag>` is a
  `FROM scratch` image holding only `/sherpa/jniLibs/arm64-v8a/*.so`
  (sherpa-onnx's libraries and the onnxruntime JNI library rebuilt
  against them) and `/sherpa/libs/*.jar`. Its tag comes from the pins in
  `scripts/build-sherpa-onnx.sh` and `scripts/build-onnxruntime-jni.sh`,
  so you rebuild it only when a pin changes.
- `convoscribe-android-builder:<sherpa tag>-tc<hash>` has JDK 17, the
  SDK (no NDK) and `gh`, with the sherpa outputs at `/opt/sherpa`.
  Tekton runs `scripts/release.sh publish` in this image. The exact tag
  is pinned in `.k8s-build.yaml`, so update it there after rebuilding.

```bash
buildah login registry.kieffer.me
bash scripts/build-images.sh --push              # both; the sherpa build takes 10-30 min
bash scripts/build-images.sh --push builder      # after a toolchain change only
bash scripts/build-images.sh --print-builder-image   # the image the Tekton task should use
# in-cluster plain-HTTP registry:
bash scripts/build-images.sh --push --tls-verify=false --registry docker-registry.docker-registry:5000
```

`publish` in Tekton reads `GH_TOKEN` and `LLM_API_KEY` from the
`convoscribe-release` Secret in the `builds` namespace. `GH_TOKEN` is a
fine-grained PAT for this repo with **Contents: read and write**; CI
never pushes to git, so that's only for `gh release create` and fetching
history. The script unshallows Tekton's shallow clone itself.

```bash
kubectl -n builds create secret generic convoscribe-release \
  --from-literal=GH_TOKEN=... --from-literal=LLM_API_KEY=... \
  --dry-run=client -o yaml | kubeseal -o yaml > convoscribe-release-sealed.yaml
```

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
per-conversation options, the display modes and organising
conversations. A troubleshooting section shows how to read
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
| `enh-gtcrn-simple` | CPU, optional | 535 638 (≈0.5 MB) | Noise cleanup (issue #37), downloaded from Settings only. `gtcrn_simple.onnx`, run by sherpa-onnx's streaming denoiser. |

Sources:

- NPU archive:
  `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models-qnn-2/sherpa-onnx-qnn-nemotron-speech-streaming-en-0.6b-560s-android-aarch64.tar.bz2`
- English ASR: `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/<file>`
- French ASR: `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/<file>`
- Segmentation: `https://huggingface.co/csukuangfj/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx`
- Embeddings: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/wespeaker_en_voxceleb_resnet34_LM.onnx`
  (the `recongition` misspelling is in the upstream tag and must be preserved).
- Noise cleanup: `https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/gtcrn_simple.onnx`

Models are downloaded on first run into
`context.filesDir/models/<bundleId>/` — see the troubleshooting
section of `docs/MANUAL_TEST.md` for the exact path and how to
inspect / clear it with `adb shell`.

The download runs in a foreground service with a progress
notification and a Cancel action, so it keeps going with the screen
off or in another app, and leaving the setup screen does not stop it.
A dropped connection is retried with backoff once the network is
back, resuming each file (and the NPU archive, which is downloaded to
`models/<bundleId>.archive.part` and extracted once complete) from
where it stopped; Retry after a failure resumes the same way. The NPU
archive needs about twice its size free while it is unpacked.

## Architecture

Single-module Android app `:app`, Kotlin + Jetpack Compose,
Material 3. Source under `app/src/main/java/com/convoscribe/`,
one package per concern:

- `audio/` — PCM16 16 kHz primitives: `WavWriter`, `WavReader`,
  `MicAudioSource` (a thin `AudioRecord` wrapper).
- `models/` — `ModelCatalog` (the bundle table above),
  `ModelDownloader` (resumable HTTPS through `ResumableFetch`,
  retries, tar.bz2 extraction), `ModelDownloadManager` (the one
  app-wide download, run by `service/ModelDownloadService`),
  `ModelStore` (the on-disk layout and per-file presence check).
- `asr/` — engine-agnostic streaming interface:
  `StreamingTranscriber`, `TranscriberFactory`,
  `TranscriptEvent` (Partial / Final), `EndpointSegmenter`,
  `TextNormalizer`.
- `diarization/` — post-record diarization pipeline:
  `DiarizeSessionUseCase` (single rule: `runOrDefer` after Stop,
  import and re-transcription follows the *Identify speakers* setting
  and may leave the session `DIARIZATION_PENDING`; a cancelled run goes
  back to pending, and `cancel()` stops the run in progress for the
  user), `PendingDiarizationRunner` (the charging job's loop, oldest
  first; held by `PendingDiarizationScheduler` after a user cancel until
  the charger is unplugged), `Diarizer`, `SpeakerTurn`, `SpeakerAssigner`.
- `engine/sherpa/` — the only package that ever imports
  `com.k2fsa.sherpa.onnx`: `SherpaRecognizerBackend`,
  `SherpaTranscriberFactory`, `SherpaDiarizer`,
  `SherpaDiarizerFactory`, `SherpaInfo`, `SherpaAsrConfigs`,
  `QnnRuntime` (`prependAdspLibraryPath`).
- `data/` — Room database (`AppDatabase`), entities and DAOs for
  sessions, segments, speakers, chat messages; `SessionRepository`
  is the single façade the rest of the app reads / writes through.
  `SegmentFtsEntity` is an FTS4 index over transcript lines (kept in
  sync by Room's triggers) that `SessionSearch` queries for search.
  Hand edits (issue #26, schema v6) set `segments.textEdited` /
  `speakerEdited` and `sessions.transcriptEdited`; `TranscriptEditor`
  computes the edited rows and `TokenAligner` re-times edited text, and
  diarization honours the line flags.
  Marks (issue #30, schema v8) are `MarkEntity` rows: a recorded-audio
  time and an optional note per session, independent of the segments.
  Tags (issue #32, schema v12) are `TagEntity` rows (unique by their
  case-folded name) linked to sessions by `SessionTagEntity`; presets
  are `PresetEntity` rows behind `PresetStore`; `sessions.titleSource`
  says whether a title is the date default, typed, or automatic.
  Since schema v16 (issue #25) every session has a `syncId` (UUID) and
  an `updatedAt`. `SessionManifest` is a session in portable form (no
  local ids or paths, recordings referenced by SHA-256, child rows in a
  fixed order), so `SessionManifests.hash` changes only when the
  session does; `SessionRepository.snapshot` builds one and
  `applyManifest` creates or replaces a session from one in a single
  transaction, moving its recordings in last. Sync (#67) reuses both.
  Schema v17 (issue #68) adds sync's own bookkeeping, keyed by
  `syncId`: `sync_state` (the revision and manifest hash the server
  last accepted), `sync_tombstones` (sessions deleted after the server
  had them) and `file_hashes` (recording hashes by size and mtime, and
  an upload in progress). Schema v18 (issue #69) adds two-way sync:
  `sync_state` also keeps the newest revision the change feed showed,
  whether the server's version won a conflict, *Remove from this
  device* (with the title and length the list shows) and recordings
  still to download, and tombstones keep the deleted length for the
  conflict rule. Every write path stamps `sessions.updatedAt` through
  `SessionRepository.touch` for "most recently changed wins".
  Schema v19 (issue #70) gives known-voice samples, presets and summary
  templates a `syncId` (and presets and templates an `updatedAt`),
  which backups now carry, and `sync_state.contentKey`, the text hash of
  the vectors the server holds for a session. `LibraryManifest` and
  `VectorsManifest` are their portable forms.
- `sync/` — two-way sync with the sync server (issues #68, #69):
  `SyncClient` (the `/api/sync/v1` routes, Bearer key), `BlobUploader`
  (resumable 8 MB parts), `FileHashCache`, and `SyncEngine`. A run reads
  the change feed into `sync_state`, pushes tombstones, then every
  session whose manifest hash differs from `sync_state` (recordings
  before the manifest), then downloads each session the server has
  newer when the local copy is unchanged or lost the conflict rule
  (applied with `applyManifest` before its recordings), and finally
  the recordings, through `ResumableFetch`, hash-checked before
  `attachRecording` puts them in place. The conflict rule itself lives
  on the server. It is run by `service/SyncJobService` (job 1104, any
  network; unmetered when only recordings wait), scheduled by
  `service/SyncScheduler` on a debounced Room invalidation of the
  session tables, so no write path is missed, and when the app opens.
  `SyncStatusStore` keeps what Settings shows and the feed cursor.
  After sessions, a run syncs the library (#70) through `LibraryKind`,
  one document per summary template, preset and known-voice sample
  (templates first, so presets resolve them by name): it downloads,
  then pushes changes with no recording length, so the most recent
  change wins. Deleted items are noticed as missing rows with a synced
  `sync_state`, so the stores need no hooks. Then semantic vectors, one
  `vectors` document per session through `SessionVectors`
  (`SemanticIndexRunner`): downloaded ones are kept only when the model
  id and the recomputed text hash match, and a device pushes its own
  only when the server has none for the session's current text.
- `backup/` — back up and restore (issue #25): `BackupFormat` (the zip
  layout: `backup.json` first, then each `sessions/<syncId>.json`
  followed by its `recordings/<sha256>.wav`, read in one pass),
  `PortableSettings`, and `BackupUseCase`, run by
  `service/BackupService` (`dataSync`, progress and Cancel).
- `settings/` — `SettingsRepository` (SharedPreferences),
  `AppSettings`, `SecretStore` (EncryptedSharedPreferences) for
  the LLM API key.
- `llm/` — `ChatClient` (OkHttp + kotlinx.serialization, SSE
  streaming), `LlmException`, `LlmMessage`, `PromptBuilder`,
  `SseParser`, `TranscriptTruncator`, `ActionItemsParser` (a summary's
  action items, #31); `MarkNotesWriter` and
  `AutoTitler` run their LLM calls on the application scope, one at a
  time.
- `recording/` — `RecordingController` (the audio + ASR loop),
  `RecordingState`, `RecordingStore`, `SessionRecovery` (the
  crash-recovery boot pass).
- `service/` — `RecordingService` (foreground microphone service),
  `ModelDownloadService` and `UpdateDownloadService` (`dataSync`
  download services sharing `DownloadLocks`), `DiarizationJobService`
  and `PendingDiarizationScheduler` (the persisted JobScheduler job that
  identifies pending speakers while charging, #11), notification text
  helper.
- `update/` — in-app updates (#15): `GitHubReleases` (the releases
  API), `SemVer`, `UpdateSelector` (which release to offer),
  `UpdateDownloader` (`ResumableFetch` + sha256), `ApkVerifier`
  (package, `versionCode`, signing key), `UpdateManager` (the app-wide
  check / download / install state), `PackageUpdateInstaller` and its
  receivers.
- `enginecheck/` — `DeviceInfo` (manufacturer / SoC / libcdsprpc /
  storage), `EngineCheckRunner`, `Report`.
- `ui/<screen>/` — one package per screen, Composable + ViewModel
  pair (`home`, `record`, `session`, `settings`, `setup`,
  `enginecheck`, `organise` for Settings → Tags and Presets); `ui/update/` holds the update banner shared by Home
  and Settings; `ui/AppNavHost.kt` is the navigation graph;
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
- **Max transcript characters** — how much transcript goes into one
  request, to fit the model's context window. The default 24 000
  characters is roughly 20–30 minutes of conversation. Longer
  transcripts are summarised in parts and questions about them use the
  most relevant parts (see *Summaries and Q&A* above), so raise it only
  if your model has room: a bigger budget means fewer, larger requests.

The endpoint is contacted only by `llm/ChatClient.kt`. The
streaming path uses server-sent events (`data: {…}\n\n` chunks)
and surfaces partial tokens to the UI as soon as the SSE parser
sees the `delta.content` field.

## Transcription server

Settings → **Transcription server** (issue #58) sends stored
recordings to a self-hosted server for a more accurate transcript than
the efficiency-focused on-device models:

- **Base URL** — the OpenAI-compatible base URL, ending in `/v1`, e.g.
  `https://asr.example.com/v1` or, inside a trusted network,
  `http://parakeet-asr.asr:8000/v1`. A plain `http://` address works
  but sends recordings unencrypted; Settings warns about it.
- **API key** — optional, sent as `Authorization: Bearer …`, stored
  encrypted apart from the LLM key.
- **Model name** — optional; blank uses the first model the server
  lists. *Test connection* lists them.
- **Improve recordings on the server** — after Stop, and for imports.

The app calls `POST {base}/audio/transcriptions` with the session's
16 kHz mono WAV (`file`), `response_format=verbose_json`,
`timestamp_granularities[]=word` and `=segment`, `language` and
`model`. It reads `segments[{start,end,text}]` as transcript lines and
`words[{word,start}]` as word timings. The word timings let speaker
identification split a line at a speaker change and playback highlight
the word being said, so use a server that returns them. A server that
only returns `text` still works, as one line. The whole recording is
uploaded in one request (about 115 MB per hour of audio), so allow
large request bodies on any proxy in front of it.

Tested with NVIDIA Parakeet TDT 0.6B v3 (25 European languages,
including English and French) behind such an endpoint on an RTX 2080 Ti:
30 minutes of audio in about 50 s, upload included. Any server with the
same API, such as speaches (faster-whisper), works too.

`ServerTranscriberLiveTest` checks a real server from the JVM:

```bash
CONVOSCRIBE_ASR_SERVER=http://parakeet-asr.asr:8000/v1 \
CONVOSCRIBE_ASR_SAMPLE_WAV=/path/to/16k-speech.wav \
./gradlew :app:testDebugUnitTest --tests '*ServerTranscriberLiveTest'
```

## Sync server

[`server/`](server/README.md) holds the self-hosted sync server (#67), a
Next.js app with Postgres and S3-compatible storage. It sits beside the
Android project with its own gate (`bash scripts/server-gate.sh`), container
image and `server-vX.Y.Z` releases. Its README covers the API,
development, configuration and deployment.

To sync a phone (#68): deploy the server, then in Settings → *Sync* enter
its address (`https://sync.example.com`, without `/api/…`) and the API key
(`SYNC_API_KEY`, or your `sk_authz_…` key behind authz-proxy), and turn on
*Sync sessions*. Do the same on every device. Each uploads its finished
sessions and downloads the others' (#69); a session that changed on two
devices is settled by the server: the longer recording wins, then the
most recent change, then (for a perfect tie) the higher manifest hash,
and a delete wins unless the other version has a longer recording. The
reverse proxy must accept request bodies of at least 8 MB. Syncing known
voices, presets, templates and semantic vectors (#70) needs a server
from `server-v0.4.0`; an older one keeps syncing sessions and the app
skips the rest.

Open the server's address in a browser, on a laptop or a tablet, to read
your sessions there (#80): sign in (with `SYNC_API_KEY`, or through
authz-proxy), browse them by day, tag and date with a preview of each,
and open one to read its transcript beside its summary, action items,
marks and questions while its recording plays; tap a sentence to play
from it.
Recordings and transcripts (Markdown, SRT or WebVTT) download from there.
You see only your own sessions, and nothing can be changed from the web
yet. See the [server README](server/README.md#web-app).

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
- **GTCRN** (optional noise cleanup) — MIT, https://github.com/Xiaobin-Rong/gtcrn.
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

### onnxruntime JNI library

Semantic search runs its text model through onnxruntime's Java API
(the `onnxruntime-android` AAR). That AAR's JNI library binds to its own
runtime's symbol version (`VERS_1.28.0`), and Android refuses to load it
against the 1.28.2 runtime sherpa-onnx ships. That runtime has to stay
because it carries the QNN provider. `scripts/build-onnxruntime-jni.sh`
(also run by `scripts/setup-toolchain.sh`) does three things:

1. Sparse-clones onnxruntime `v1.28.0` (the AAR's version) into
   `~/.local/share/convoscribe-toolchain/onnxruntime-jni-build/`.
2. Generates the JNI headers with `javac -h`.
3. Compiles the JNI sources with the NDK, without training, CUDA or
   TensorRT. It links them against `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so`
   and writes `libonnxruntime4j_jni.so` next to it.

The packaging rules in `app/build.gradle.kts` keep these two libraries
over the AAR's. Run it again after rebuilding sherpa-onnx.

## Test

```bash
source scripts/env.sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleDebug
bash scripts/server-gate.sh   # the sync server in server/: lint, typecheck, vitest
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
