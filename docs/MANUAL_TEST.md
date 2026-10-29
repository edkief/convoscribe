# Manual on-device test guide

This guide walks the owner through validating ConvoScribe on a
real Qualcomm Dragonwing Q-6690 device. The agent loop cannot run
on-device tests (no emulator, no instrumented JVM, the sherpa-onnx
native library refuses to load off-device), so the project's PRD
acceptance criteria depend on this checklist being run by hand.

Each check has a **Steps** block ("what you do") and an **Expected**
block ("what should be true afterwards"). Please tick the box, copy
the report values into the matching row of
[`.ralph/artifacts/TASK-29/report.md`](../../.ralph/artifacts/TASK-29/report.md),
and file any deviation under "Troubleshooting" below.

The shared log tag the app uses is `ConvoScribe`; the native engine
adds `sherpa-onnx`. The combined filter is:

```bash
adb logcat -s ConvoScribe:I sherpa-onnx:I AndroidRuntime:E
```

Dev environment that the scripts expect:

```bash
source scripts/env.sh
./gradlew installDebug
```

Run `bash scripts/build-apk.sh` to build and copy the APK to `dist/convoscribe-<versionName>-debug.apk` (the wrapper prints the exact path). `installDebug` and `build-apk.sh` produce the same APK; `build-apk` also runs the full verify command (ktlint + tests + lint + assemble) and writes the versioned copy. Add `--install` to also install it on the connected device once the build succeeds (set `ANDROID_SERIAL` to pick one of several).

Install (same as the README):

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 1. Install

**Steps**

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**Expected** — `Success` from `adb`; the launch icon appears in the
device app drawer as "ConvoScribe"; launching it lands on the model
setup screen (or the home screen if you've already downloaded the
model before).

## 2. First-launch model download

**Steps**

1. Open ConvoScribe. On a fresh install the model setup screen
   appears; tap **Download**. On Android 13+ allow notifications when
   asked (denying them does not stop the download).
2. Observe the progress bar and the **Downloading speech models**
   notification (MB done / total, Cancel); on a slow link it takes
   ~5–10 minutes for the NPU bundle (~420 MB compressed body,
   ≈610 MB extracted).
3. Mid-download, turn on airplane mode for ~30 s, then turn it off.
   Watch the bar and the notification.

**Expected** — while the network is down, the screen and the
notification say the download resumes when back online; once the
network is back the bar continues from the byte count it had reached
(no "start over" jump), for a plain file as well as for the NPU
archive. After the download, the NPU bundle shows **Unpacking…** with
a percentage. Once complete, the screen pops to **Home** and the
notification changes to **Speech models downloaded**. The logcat line
below appears once when the download completes:

```
I ConvoScribe(12345): model download completed bytesDone=… bundles=…
```

### 2a. Download in the background (issue #10)

Run on the Samsung device and on the Boox, with a fresh install (or
the models deleted in Settings).

1. Tap **Download**, then turn the screen off for a few minutes.
   Turn it on: the download has kept going (the bar is further on),
   and it completes with the screen off.
2. Start again; press Home and use another app for a while, then
   come back: the setup screen shows the live progress.
3. From Settings, open the model download for another language
   (`back` route), tap **Download**, press Back, then reopen it: the
   screen re-attaches to the running download instead of starting a
   second one.
4. Tap **Cancel** in the notification (or on the screen): the
   download stops, the notification goes away and
   `adb shell run-as com.convoscribe ls files/models` shows no
   `.part` / `.archive.part` files left.
5. Start again, then force-stop the app (`adb shell am force-stop
   com.convoscribe`) mid-file. Reopen and tap **Download**: it
   resumes from the partial file (logcat shows `resuming <file> from
   byte N`, and the bar starts from the earlier byte count).
6. Android 15+: after a long time in the background the system may
   stop a `dataSync` service; the notification then says the download
   failed with **Android paused the download**, and **Retry** resumes
   it.

## 2b. Engine check — NPU

**Steps**

1. From Home, open **Engine check**. The card at the top shows the
   device metadata (Manufacturer, Model, SoC, Android, Free storage,
   CDSP library present, HTP skel libraries).
2. Verify **Free storage ≥ 1.5 GB** before the first NPU run; if
   it is below 1.5 GB the screen shows the warning banner.
3. Tap **Check NPU**. The label flips to **Running… (the first NPU
   run prepares the model and can take minutes)**.
4. When the result card appears, copy the full report via the
   **Copy report** button.

**Expected** — first NPU start runs the one-time QNN context
preparation and may take several minutes; later runs are much
faster. The report includes:

* Device: Manufacturer / Model / SoC / Android
* First run: `…` ms (the time spent compiling the QNN context the
  first time; subsequent runs show `0 ms`)
* Init: `…` ms (recognizer ctor)
* Audio: `…` ms (WAV reader pre-flight)
* Processing: `…` ms (decode of the bundled sample wave)
* RTF: a real-time factor `< 0.5` on the NPU (lower is better)
* Text: the recognised transcript from the sample `test_wavs/0.wav`

If RTF is `> 1.0` or `Text:` is empty, the recognizer cannot reach
the Hexagon DSP — see Troubleshooting §1 below.

## 2c. Engine check — CPU

**Steps**

1. From the Engine check screen, tap **Check CPU**.
2. Copy the report.

**Expected** — the CPU engine starts immediately (no one-time
preparation) and produces a higher RTF (typically `0.6` – `1.2` on
the Q-6690). The recognised text matches what the NPU produced
(allowing for case normalisation).

## 3. Microphone permission

**Steps**

1. From Home, tap **New recording**. The system permission dialog
   appears the first time.
2. Tap **Deny**.
3. Return to Home, then tap **New recording** again. The dialog
   reappears.
4. Tap **Allow**.

**Expected** — Denying leaves the screen on a "Microphone
permission is required to record" caption; allowing takes the user
to the recording screen with a **Start** button.

## 4. Live transcription

**Steps**

1. On the recording screen with the NPU engine selected, tap
   **Start**.
2. Two people speak English conversationally for 2 minutes.
3. Watch the live partial text field.

**Expected** — partial text appears within ~1 s of speech; final
lines appear below the partial as the recognizer closes them
(typically every 1–3 s).

## 5. Screen-off during recording

**Steps**

1. With recording still going, press the power button to turn the
   screen off.
2. Wait 30 seconds.
3. Turn the screen back on.

**Expected** — the persistent recording notification is visible
throughout; tapping it brings the app back to the recording screen
with the session still in `RECORDING`; the **Stop** action on the
notification stops the session.

## 6. Post-stop diarization

With Settings → Speakers → *Identify speakers* on **Auto** (the
default). The other modes are covered in §25.

**Steps**

1. Tap **Stop** on the recording screen.
2. Watch the session-detail screen for "Identifying speakers…".
3. Note the wall-clock time between **Stop** and the appearance of
   the **Speaker 1** / **Speaker 2** labels.

**Expected** — the duration is roughly `audio_length_ms / 1000`
seconds for the post-stop diarization pass; for a 2-minute
recording expect ~15–60 s. Each recognised line is prefixed by a
speaker label.

## 7. Rename speakers and session, restart

**Steps**

1. Open the session's overflow menu → **Rename speaker**, rename
   `Speaker 1` to `Alice` and `Speaker 2` to `Bob`. Save.
2. **Rename session** to a name of your choice.
3. Press Home to leave the session. Force-stop ConvoScribe
   (`adb shell am force-stop com.convoscribe`) to ensure a cold
   start.
4. Relaunch ConvoScribe.

**Expected** — the session still appears in Home with the renamed
title; opening it shows the lines as `Alice: …` / `Bob: …`.

## 8. Share transcript

**Steps**

1. From the session-detail screen, tap the overflow menu (top right)
   → **Share transcript**.
2. Pick a notes app from the system share sheet.

**Expected** — the notes app receives a plain-text version of the
formatted transcript (one line per segment, speaker label included).

## 9. LLM endpoint

**Steps**

1. From Home, open **Settings**.
2. Scroll to **Language model**.
3. Pick an OpenAI-compatible endpoint you have running (vLLM,
   llama.cpp server, Ollama with `http://<host>:11434/v1`, …). The
   base URL must end with `/v1`.
4. Paste the API key (if the endpoint needs one).
5. Type the model id (e.g. `meta-llama-3.1-8b-instruct`).
6. Tap **Test connection**.

**Expected** — the result line reads `Connected – N models: …`
and a chip list appears with the model ids. On failure
(`HTTP 401`, network unreachable, bad base URL), the line reads
`Failed: …` and the chips disappear.

## 10. Summarise

**Steps**

1. In a session that has transcript, open the **Summary** tab.
2. Tap **Summarise**.

**Expected** — a streaming summary appears token by token; the
final summary is saved with the session so a future open shows it
without regenerating. It is formatted: section headings, bold text
and bullet / numbered lists render as such, with no raw `#`, `**` or
`- ` markers (same for the notes under marks). **Copy** puts the raw
Markdown on the clipboard.

## 11. Ask questions (context)

**Steps**

1. In the same session, open the **Ask** tab.
2. Ask "When did they say they would follow up?"
3. Ask "Who said that?" (a follow-up that needs prior context).

**Expected** — right after Send, a bubble with three pulsing dots
appears on the answer side (on e-ink: three still dots and
*Generating answer…*) until the first words arrive; then both
questions stream token by token, with the answers formatted
(lists, bold) rather than raw Markdown. The second
answer uses the first question's answer / the transcript as
context (no "I don't have context for this question" error).
Stop while the dots show removes them.

## 12. French

**Steps**

1. Tap **New recording**, pick **Français** under **Language** (the
   default comes from **Settings** → **Transcription**).
2. Tap Start; if the French bundle (~71 MB) is missing, setup opens,
   downloads it and returns to the Record screen. Tap Start again.
3. Speak French for ~30 s.

**Expected** — the CPU engine is used (the selector shows
"French uses the CPU engine" hint); partial text appears within
~1 s and resembles the French you spoke.

## 13. Delete session

**Steps**

1. From Home, swipe-to-delete (or overflow → delete) a session.

**Expected** — the session disappears from the list; the on-disk
`recordings/<sessionId>.wav` and the database row are removed
(see §5 in Troubleshooting to verify with `adb shell`).

## 14. Force-stop during recording

**Steps**

1. Start a recording and speak for ~30 s.
2. While recording is in progress, run
   `adb shell am force-stop com.convoscribe`.
3. Relaunch ConvoScribe.

**Expected** — on relaunch the session appears in the list with
status `TRANSCRIBED` and the partial transcript captured so far
(Step 14 exercises the `SessionRecovery` pass in
`recording/SessionRecovery.kt`).

## 15. Performance (CPU%)

**Steps**

1. Start a recording with the NPU engine selected.
2. While recording is in progress, in another terminal:

   ```bash
   adb shell top -m 5 -b -n 1 | head
   ```

3. Stop, switch the engine to CPU in **Settings**, start another
   recording, repeat `adb shell top -m 5`.

**Expected** — the NPU run shows a higher user-space CPU% for the
recognizer process but a lower total CPU because the Hexagon DSP
does the heavy work; the CPU run shows the opposite. The model
download (~450 MB compressed) and diarization (CPU-only) both
saturate the CPU.

## 16. Engine switching NPU → CPU → NPU

**Steps**

1. With the **NPU** engine selected, run a fresh recording, speak
   English for ~30 s, stop.
2. Open **Settings** → switch to **CPU**, save (or just tap the
   other segment), start a recording, speak English for ~30 s,
   stop.
3. Switch back to **NPU**, start a recording, speak English for
   ~30 s, stop.

**Expected** — three sessions in Home, each with a transcript. The
NPU sessions after the first one are fast to start (the QNN
context binaries are cached next to the encoder/decoder/joiner
shared libraries).

## 17. Per-conversation options

**Steps**

1. Tap **New recording**: Language, Number of speakers, Expected
   participants and Title show the Settings defaults.
2. Pick **Français**, 2 speakers, a title; record ~20 s, stop.
3. Tap **New recording** again: the defaults are back (English,
   Automatic, no title). Record ~20 s in English, stop.
4. Open Settings: language and speaker count are unchanged.
5. Tap **New recording**, choose Français and 3 speakers, tap
   **Set as default**, then go back. Open **New recording** again.

**Expected** — no visit to Settings is needed for steps 2–3; each
session keeps its own language (overflow → **Session info**) and
title. After step 5 the Record screen and Settings both start from
Français / 3. *Continue recording* on the French session stays in
French, and *Re-identify speakers…* preselects 2.

## 18. Expected participants

**Steps**

1. Enroll two people (Settings → Speakers), e.g. Alice and Bob.
2. Start a recording with only **Alice** expected; have Bob speak.
3. After diarization, overflow → **Participants…**, tick Bob too,
   Save, accept **Re-identify speakers now?**.

**Expected** — in step 2 Bob's voice is never labelled "Bob" (it is
"Alice" only if it matches her, else "Speaker N"). After step 3 Bob
is matched. **Session info** lists the participants.

## 19. Session info on older sessions

**Steps**

1. Install this build over a build from before issue #6 that has
   existing sessions; open an old session → overflow → **Session info**.

**Expected** — language and speaker count are as before; engine and
model show "unknown". New sessions show e.g. `cpu` /
`asr-en-kroko-2025-08-06`.

## 20. Re-transcribe in another language

**Steps**

1. Open an English session with a summary, overflow →
   **Re-transcribe…**, pick Français (download it first if asked),
   confirm.
2. Watch the banner and notification progress; leave the app.
3. Repeat on another session and tap **Cancel** halfway (banner or
   notification).

**Expected** — the dialog warns that the summary and questions were
based on the old transcript. Step 1 replaces the transcript with a
French one, speakers are identified again, and names you typed are
kept. Once the new transcript shows, the notification switches to
*Identifying speakers…* and disappears when that finishes; it never
stays behind at its last percentage (issue #43). After the cancel in
step 3 the previous transcript, language and speakers are unchanged. **Start** on the Record screen is
disabled while a re-transcription runs.

---

## 21. Display mode and the new design (issue #4)

**Steps**

1. On a phone, open the app: Home shows session cards (date,
   duration, speakers, a status badge while recording / identifying
   speakers / failed) and a teal **New recording** button. Delete a
   session from its card's ⋮ menu; long-press still works.
2. Settings → **Appearance**: switch **Display** between Auto,
   Standard and E-ink. The app re-themes at once, without a restart.
3. On the **Boox Note Air 6C**, with Display on **Auto**: the app is
   black on white with outlined cards, no ripple on taps and no
   scrolling animation (the live transcript and the playing line
   jump). Standard turns the colours back on; E-ink works on a phone
   too.
4. On Android 12+, Standard mode: turn **Use device colours** on and
   off; the colours follow the wallpaper, then return to teal. The
   switch is hidden in E-ink.
5. Record ~30 s: the timer, the **Recording** badge and the
   microphone level meter move while you speak and the meter drops
   when you are silent.
6. Open a diarized session, rename *Speaker 2* (chip or line): the
   chip's dot and that speaker's lines keep the same colour.
7. System dark mode on (Standard): every screen is dark and readable.
8. System font size at the largest setting (200 %): Record, Session
   and Settings stay usable; choices become radio lists, the session
   tabs scroll, nothing is cut off mid-word.
9. With TalkBack, the icon buttons (Settings, ⋮, share, mic, send,
   delete) announce what they do.

**Expected** — as described in each step; no behaviour changed
compared with the previous build (recording, diarization, playback,
summaries and questions work as in checks 4–20).

---

## 22. Import audio (issue #3)

Prepare, on the device (e.g. in `Download/`), a few minutes of
two-person speech in each of: **MP3**, **M4A** (AAC), **OGG/Opus**
(a messenger voice note), **FLAC**, and **stereo WAV at 44.1 kHz**
and **at 48 kHz**. Also a corrupt file (rename a `.txt` to `.mp3`)
and a video-free file with no audio track if you have one.

```bash
# e.g. from one source file, with ffmpeg on the host:
ffmpeg -i talk.wav -ac 2 -ar 44100 talk-44k.wav
ffmpeg -i talk.wav -ac 2 -ar 48000 talk-48k.wav
ffmpeg -i talk.wav talk.mp3 && ffmpeg -i talk.wav -c:a aac talk.m4a
ffmpeg -i talk.wav -c:a libopus talk.ogg && ffmpeg -i talk.wav talk.flac
adb push talk-44k.wav talk-48k.wav talk.mp3 talk.m4a talk.ogg talk.flac /sdcard/Download/
```

**Steps**

1. Home → ⋮ → **Import audio…**, pick `talk.mp3`. Watch the banner
   (*Reading the file…*, *Decoding the audio…*, *Transcribing… N %*)
   and the notification.
2. Repeat step 1 for the M4A, OGG/Opus, FLAC and both WAV files.
3. From a file manager, long-press a file → **Open with** →
   ConvoScribe. From a messenger (or the Files app), **Share** a voice
   note → ConvoScribe.
4. Start a long import, turn the screen off for a minute, then come
   back.
5. Start another import and tap **Cancel** (banner or notification)
   while it is transcribing.
6. Import the corrupt file, and the file without audio.
7. Start a recording, then share a file to ConvoScribe. Then start an
   import and open **New recording**.
8. Switch the language to Français in Settings with the French model
   not downloaded, then import a file.

**Expected**

- Steps 1–3: each import opens a new session titled after the file
  name (renamable), with playback, a transcript with sensible text
  and line timings (tap a line: playback starts at that point), and
  speakers identified afterwards. Rename, *Re-identify speakers…*,
  Summary and Ask work as for a recording; *Session info* shows the
  Settings language and the engine. The notification follows each
  step as soon as it starts: it says *Identifying speakers* as soon as
  transcription ends, not after a delay at the last percentage
  (issue #43).
- Step 4: the import keeps going with the screen off and the app in
  the background; progress resumes on screen.
- Step 5: the import stops; no new session appears and
  `files/recordings/` has no new WAV (`cache/imports/` is empty).
- Step 6: a snackbar says the file can't be read / has no audio; no
  session is created.
- Step 7: the share is refused with *Something is already recording
  or importing…*; during the import the Record screen's Start is
  disabled with *An audio file is being imported…*.
- Step 8: the app opens setup for the French model; after the
  download it returns to Home and the import starts. Backing out of
  setup instead shows *Importing needs the transcription models…*.

## 23. Record from anywhere (issue #7)

Run this on an **Android 14+** phone and on the **Boox**. Add the
tile first: pull down Quick Settings twice → edit (pencil) → drag
**ConvoScribe** into the active tiles. For the permission-denied
path, revoke *Display over other apps* first:

```bash
adb shell appops set com.convoscribe SYSTEM_ALERT_WINDOW deny
```

**Steps**

1. Fresh install (or *Clear data*). New recording → Start. Read the
   *Control recordings from other apps?* dialog and tap **Not now**.
   Speak for ~20 s, then switch to another app.
2. Back in ConvoScribe, tap **Pause** on the Record screen; wait 30 s
   while speaking; tap **Resume** and speak another ~20 s. Stop.
3. Start another recording, switch to another app and use the
   notification: **Pause**, wait, **Resume**, **Stop**.
4. Grant the permission: Settings → Recording → **Allow display over
   other apps** (or `adb shell appops set com.convoscribe
   SYSTEM_ALERT_WINDOW allow`). Start a recording and switch to
   another app.
5. On the floating control: **Pause**, wait 30 s, **Resume**; drag it
   to the other side and to the bottom, release; tap its time to open
   the Record screen; leave again; **Stop** from the control.
6. Turn Settings → Recording → *Floating control over other apps* off,
   start a recording, switch to another app. Turn it back on.
7. Force-stop ConvoScribe (`adb shell am force-stop com.convoscribe`)
   or swipe it from Recents. Open another app, pull down Quick
   Settings and tap **ConvoScribe**. Speak, then tap the tile twice
   (pause, resume), then Stop from the notification.
8. Repeat step 7 with the screen locked (tile from the lock screen).
   Repeat once with the microphone permission revoked
   (`adb shell pm revoke com.convoscribe android.permission.RECORD_AUDIO`).
9. Start a recording, Pause, then `adb shell am force-stop
   com.convoscribe`. Open the app.
10. Pause a recording and leave it paused for more than an hour (or as
    long as practical) with the screen off.

**Expected**

- Step 1: the dialog appears once; after *Not now* it never appears
  again on later Starts. No floating control appears (no permission);
  the notification shows *Recording – mm:ss* with **Pause** and
  **Stop**.
- Step 2: while paused the badge says *Paused*, the timer stops, the
  level meter is gone, the status-bar mic indicator turns off and the
  notification says *Paused – mm:ss* with **Resume**. After resuming,
  the timer continues from where it stopped. The session has one
  recording (no second *Identifying speakers* run before Stop); the
  words before the pause end their line; on playback the speech after
  the pause follows straight on, and tapping a line after the pause
  plays that line (times are aligned with the audio).
- Step 3: the notification actions work from another app; the
  service stays in the foreground while paused.
- Steps 4–5: the control appears over the other app within a second,
  shows *Recording* / *Paused* and the time, and follows the session
  from any of the three places. It follows the finger, rests against
  the nearest side edge, and comes back at the same place next time;
  it hides while ConvoScribe is in front and disappears on Stop. On
  the Boox (e-ink mode) it is outlined with no shadow and nothing
  animates.
- Step 6: no control while switched off; the notification still works.
- Step 7: a recording starts with the Settings defaults without
  opening the app (a blink at most). The tile shows *Recording*, then
  *Paused*, then *Recording*, then goes back to *Record* after Stop.
  **Write down whether this works on Android 14+ and on the Boox; if
  the start or a resume from the background fails (no audio, or a
  `ForegroundServiceStartNotAllowedException` / `SecurityException`
  in logcat), note the Android version and the exact message.**
- Step 8: from the lock screen the tile asks to unlock first, then
  starts. Without the mic permission the tile opens the Record screen,
  whose Start asks for the permission.
- Step 9: the session is in the list as a finished session, with its
  transcript up to the pause and its audio playable.
- Step 10: the session is still paused, the notification is still
  visible, and Resume records again.

## 24. In-app update (issue #15)

The app checks `api.github.com/repos/edkief/convoscribe/releases` and
offers a release newer than its own `versionName`. To have something
to update to, install a build that is **older than the latest
release**. Build it with a lower version, without committing the
change (the shared `app/debug.keystore` signs it like the releases):

```bash
sed -i 's/versionCode = .*/versionCode = 1/; s/versionName = ".*"/versionName = "0.0.1"/' app/build.gradle.kts
./gradlew installDebug
git checkout app/build.gradle.kts
```

To publish a test release instead, use `scripts/release.sh publish
--draft` on a throwaway tag (a draft is not offered; publish it in the
GitHub UI for the test and delete it after) or a pre-release (offered
only with *Include pre-releases* on).

The automatic check runs at most once every 30 minutes: when the app
comes to the foreground (a fresh start or a return to a running app)
and from a periodic background job (issue #41). To make it due again
(this also forgets a found offer, a skipped version and the
notification already posted):

```bash
adb shell run-as com.convoscribe rm shared_prefs/updates.xml
adb shell am force-stop com.convoscribe
```

To run the background job now, with the app closed (job id 1102;
`adb shell dumpsys jobscheduler com.convoscribe` shows it scheduled
every hour, needing a network):

```bash
adb shell cmd jobscheduler run -f com.convoscribe 1102
```

Filter logcat with `adb logcat -s ConvoScribe:I` (lines start with
`update check:` / `update install status=`).

**Steps**

1. Start ConvoScribe (old build). Within a few seconds Home shows
   *ConvoScribe X.Y.Z is available*. Tap **View notes**: the release
   notes appear; close them.
2. Tap **Not now**: the banner goes. Settings → About shows
   *Version 0.0.1 (1)* and the same offer. Force-stop and reopen: the
   banner is back (the offer is remembered) but logcat shows no new
   `update check:` line (checked less than 30 minutes ago).
3. Clear the throttle (above), reopen, tap **Skip this version**.
   Clear the throttle again and reopen: no banner. Settings → About →
   **Check for updates** still offers it (a manual check ignores the
   skip).
4. Tap **Update**. A *Downloading ConvoScribe X.Y.Z* notification
   with progress and Cancel appears. Turn the screen off for a minute,
   then switch to another app; come back: the download kept going.
5. Tap **Cancel** in the notification: it stops, and `adb shell
   run-as com.convoscribe ls files/updates` is empty. Tap **Update**
   again; force-stop mid-way, reopen, **Update**: logcat shows
   `resuming convoscribe-X.Y.Z.apk from byte N`.
6. When done, the notification says *ready to install*. Start a
   recording, go back to Home and tap **Install**: a message says to
   finish the recording first. Stop it.
7. Fresh install only (or after turning the access off in system
   Settings → Apps → ConvoScribe → *Install unknown apps*): tap
   **Install**. A dialog explains the access; **Open settings**, turn
   it on, press Back: the system install prompt appears. Tap
   **Cancel** on it: the banner shows **Install** again.
8. **Install** → confirm. ConvoScribe closes; a notification says
   *ConvoScribe updated to X.Y.Z*. Tap it: the app opens on the new
   version (Settings → About), every session is still there and plays.
   `files/updates` is empty.
9. Signature mismatch: build the old version with a different key
   (move `app/debug.keystore` aside for the build so the per-machine
   debug key signs it, `adb uninstall com.convoscribe`, then
   `./gradlew installDebug`; put the keystore back). Run steps 1 and
   4: after the download the banner says the release is signed with a
   different key and offers **Open release page**; nothing is
   installed and `files/updates` is empty.
10. Settings → About → turn *Check for updates automatically* off,
    clear the throttle and reopen: logcat shows no `update check:`
    line. **Check for updates** still works. With *Include
    pre-releases* on, a pre-release newer than the installed version is
    offered too.
11. Offline (airplane mode) with the throttle cleared: no banner, no
    error. **Check for updates** says it could not reach GitHub.
12. Return to a running app (issue #41): open ConvoScribe, press Home
    (do not swipe it away), clear the throttle with only the `rm`
    line above (no force-stop), then reopen it from the launcher:
    logcat shows `update check:` and the banner appears without the
    app restarting.
13. Background check (issue #41): clear the throttle, swipe
    ConvoScribe away from recents, then run the job (above). Within a
    few seconds a *ConvoScribe X.Y.Z is available* notification
    appears. Dismiss it, set the device clock 9 hours ahead and run
    the job again: logcat shows a new `update check:` but no second
    notification for the same version. Set the clock back.
    Tap it: Home shows the banner. Repeat with *Check for updates
    automatically* off: `dumpsys jobscheduler` no longer lists job
    1102.

**Expected**

- Steps 1–3: the banner and About follow the offer, the throttle and
  the skip as described; app start is not delayed (the list shows at
  once, the banner appears later).
- Step 4: the download survives screen-off and app switching.
- Steps 5–6: Cancel deletes the part file; a force-stop resumes; no
  install while a recording is open.
- Steps 7–8: the access is explained once; the system prompt always
  appears; the new version starts with all sessions. **Write down the
  device and Android version, and whether the post-update notification
  appeared.**
- Step 9: the certificate check refuses the update with the
  reinstall message (this is the #16 path).
- Steps 10–11: no requests with the automatic check off; failures are
  silent on start and reported on the manual check.
- Steps 12–13: a running app checks when it comes back to the front;
  the closed app checks from the job and notifies once per version;
  the job follows the switch.

## 25. Identify speakers while charging / on demand (issue #11)

Charging can be simulated over adb instead of using a cable.
`dumpsys battery unplug` makes the device report it is on battery;
`dumpsys battery set ac 1` makes it report it is plugged in;
`dumpsys battery reset` returns to the real state. The charging job
can be forced to run with `adb shell cmd jobscheduler run -f
com.convoscribe 1101`, and `adb shell dumpsys jobscheduler | grep -A5
com.convoscribe` shows whether it is scheduled. Follow `adb logcat -s
ConvoScribe` for `diarization deferred`, `speaker job scheduled`,
`charging job:` and `diarization interrupted` lines.

**Steps**

1. Settings → Speakers → *Identify speakers* → **On demand**. Record
   ~30 s and tap **Stop**. Also import a file (§22) and re-transcribe a
   session (§20).
2. On the session screen, tap **Identify speakers now**.
3. *Identify speakers* → **Charging**, `adb shell dumpsys battery
   unplug`. Record two short sessions and stop each one.
4. Swipe ConvoScribe away from Recents (or `adb shell am kill
   com.convoscribe`), then `adb shell dumpsys battery set ac 1` (or
   plug in a real charger with the screen off and wait).
5. Still in **Charging**, while plugged in: record and stop.
6. Record a session of 10+ minutes in **Charging**, unplugged. Plug in,
   wait for `charging job: identifying speakers` in logcat, then
   unplug (or `dumpsys battery unplug`).
7. Start identifying speakers on a long session, then force-stop the
   app (`adb shell am force-stop com.convoscribe`) while the session
   shows *Identifying speakers…*. Reopen the app.
8. With a session pending, switch to **Auto**. Nothing should start.
   Plug in.
9. Android 14+: a session of an hour or more in **Charging**,
   unplugged, then plug in and leave it.
10. Cancel (issue #64). Record ~5 minutes in **Auto** and stop. While
    *Identifying speakers…* shows, tap **Cancel** on the notification.
    Then tap *Identify speakers now* and, on the session screen, tap
    **Cancel** under *Identifying speakers…*.
11. With two sessions pending in **Charging**, plug in, wait for
    `charging job: identifying speakers`, open the session being
    identified and tap **Cancel**. Stay plugged in for a few minutes;
    then force-stop and reopen the app, still plugged in.
12. Unplug (`dumpsys battery unplug`), then plug in again.
13. Import a file (§22); while the notification says it is identifying
    speakers, tap **Cancel** on it.

**Expected**

- Step 1: each one ends with the transcript, a *Speakers pending* badge
  in the list and *Speakers not identified yet* on the session; no
  *Identifying speakers* notification. A re-transcribed session keeps
  its old speaker names on the new lines.
- Step 2: speakers are identified as usual (the
  *Identifying speakers* notification appears); a re-transcribed
  session keeps the names typed before.
- Step 3: the banner also says *Waiting for the charger*; the job is
  scheduled.
- Step 4: with the app closed, both sessions are identified one after
  the other, oldest first; opening the app shows them labelled.
  **Write down the device, Android version and how long it took.**
- Step 5: identified straight away, as in **Auto**.
- Step 6: the session goes back to *Speakers pending* within a few
  seconds of unplugging (not *Speaker detection failed*, not stuck on
  *Identifying speakers*) and is done on the next charge. **Write down
  how long the stop took.**
- Step 7: on reopening, the session is *Speakers pending*, and it is
  identified on the next charge.
- Step 8: no work starts on the switch; pending sessions are
  identified once plugged in.
- Step 9: if the job is stopped after about 10 minutes, a notification
  *Speakers not identified yet* appears; tapping it opens the app,
  where **Identify speakers now** finishes it. **Write down whether it
  happened and how long the session was.**
- Step 10: within a few seconds the CPU drops, the notification goes
  away and the session shows *Speakers not identified yet* with its
  transcript. Logcat shows `diarization interrupted ... byUser=true`.
  **Write down how long the stop took.**
- Step 11: the run stops, logcat shows `speaker job held until the
  next charge`, and nothing starts again while plugged in, also after
  the restart. *Identify speakers now* still works.
- Step 12: `speaker job hold lifted` on unplugging; both sessions are
  identified once plugged in.
- Step 13: the imported session is kept with its transcript and shows
  *Speakers not identified yet*.

## 26. Floating control shapes (issue #21)

Run this on a phone and on the **Boox**, with *Display over other
apps* allowed and Settings → Recording → *Floating control over other
apps* on. On a fresh install (or *Clear data*), do not touch the
*Shape* setting before step 1.

**Steps**

1. Open Settings → Recording and read *Shape*. Start a recording and
   switch to another app.
2. Tap the arrow (**˅**) at the end of the control three times,
   looking at the control after each tap. Drag it to the left edge
   before the second tap.
3. In **Minimal**, leave the Boox recording for 30 s while speaking,
   with the control over a static page (a document or the launcher),
   and watch the control.
4. Tap the dot in Minimal; leave the app again. Pause and Resume from
   the control in each shape.
5. In **Expanded**, speak a few sentences. On the phone, then on the
   Boox, watch the text.
6. With TalkBack on, move through the control in each shape.
7. Stop. Open Settings → Recording, choose another shape, start a
   recording and leave the app. Turn *Floating control over other
   apps* off and on.
8. Set the system font size to the largest and repeat step 5 on the
   phone.

**Expected**

- Step 1: *Shape* shows **Minimal** on the Boox and **Standard** on
  the phone. It is only shown while the floating control is on.
- Step 2: the shapes go Standard → Expanded → Minimal → Standard on
  the phone (Minimal → Standard → Expanded → Minimal on the Boox). The
  arrow points down (*Show more*) except on Expanded, where it points
  up (*Show less*). The control stays against the edge it rests on, at
  the same height, fully on screen, also after it grows. Settings →
  Recording → *Shape* follows each change.
- Step 3: **no refresh flashes** on the Boox for the whole 30 s;
  Minimal shows no time and no text.
- Step 4: the dot opens the Record screen. Pause/Resume works in every
  shape; in Minimal only the dot colour and the Pause/Play icon change.
- Step 5: the card shows *Listening…* until the first sentence, then
  the end of the last two sentences, with the newest words always
  visible (a leading *…* when cut). On the phone the words being
  recognised appear live; on the Boox the text changes only when a
  sentence is finished, and the card never changes size.
- Step 6: TalkBack reads *Recording* / *Paused* on the dot in Minimal,
  and *Show more* / *Show less* on the arrow.
- Step 7: the control opens in the shape chosen in Settings; turning
  it off and on keeps the shape.
- Step 8: the last words are still visible at the end of the card,
  not cut off.

## 27. Search across sessions (issue #24)

Run this on a phone and on the **Boox**. You need a few sessions:
at least one in French with accented words (e.g. *réunion*, *été*),
one in English, a renamed speaker (e.g. *Élodie*), a session with a
saved summary, and sessions on different days. To check the upgrade,
install this build **over** v0.4.0 with those sessions already
recorded, without clearing data.

**Steps**

1. On the phone, tap the search icon on Home (on the Boox, the
   *Search conversations…* field above the list).
2. Type `reunion` (no accent), then `RÉUNION`, then `reun`.
3. Type the renamed speaker's name without accents (`elodie`), then a
   word from the saved summary, then a word from a session title.
4. Type two words that are said in the same line, then two words that
   are never in the same line, title or summary.
5. Tap a transcript line in the results.
6. Go back and tap a summary result; go back and tap a session's
   title.
7. Choose *Français* in the language chip, *Last 7 days* in the date
   chip, then a person in the third chip; set them back to *Any*.
8. On the Boox, type a word of six letters at a normal speed and
   watch the screen; then type and press the keyboard's Search key.
9. Delete a session that matched and search again; re-transcribe or
   re-identify speakers on another and search for one of its words.

**Expected**

- Step 2: all three find the French lines with *réunion*; the matched
  word is highlighted (bold; also underlined on the Boox) and each
  line shows its speaker and time. Results are grouped per session,
  newest first; *Show N more lines* reveals the rest.
- Step 3: the speaker, summary and title each find their session; the
  card shows *Speakers* or *Summary* with the match highlighted.
- Step 4: a line with both words is found; no result for words that
  are never together.
- Step 5: the session opens on the Transcript tab, scrolled to that
  line, which is highlighted; the player shows the line's time and is
  **not playing**. Play starts from there.
- Step 6: the summary result opens the Summary tab; the title opens
  the Transcript tab at the top.
- Step 7: each filter narrows the results at once; with no match the
  screen says so and mentions the filters.
- Step 8: the Boox refreshes the results **once** after you stop
  typing (no refresh per letter); the Search key shows results at once.
- Step 9: the deleted session no longer appears; the re-processed
  session is found by its new text.
- After the upgrade from v0.4.0, sessions recorded before it are found
  too.

## 28. Edit the transcript (issue #26)

Run this on a phone and on the **Boox**, on a diarized session with at
least three speakers' worth of lines and word timings (recorded with
this build or v0.3.0+). To check the upgrade, install over v0.5.0
without clearing data first.

**Steps**

1. Long-press a line. Choose *Edit text*, fix a word (e.g. a name) and
   add a comma, then Save. Play the line.
2. Tap *Undo* in the snackbar.
3. *Change speaker* on a line → another existing speaker; then on
   another line → *New speaker* named `Dana`.
4. *Merge with next* on a line; *Split here* on a long line with the
   cursor inside a word in the middle.
5. *Delete line*, then *Undo*.
6. Edit one line, then delete another, then tap *Undo* on the second
   snackbar only.
7. Session menu → *Re-identify speakers…* → Automatic.
8. Summarise, ask a question about the corrected word, share the
   transcript, and search Home for the corrected word.
9. Session menu → *Re-transcribe…*, pick a language, confirm.
10. With TalkBack on, focus a line and open the actions menu
    (swipe up then right, or the TalkBack menu → Actions).
11. Start re-identifying speakers and long-press a line while it runs.

**Expected**

- Step 1: the line shows the new text and *edited* after its time;
  playback still highlights word by word, including on the changed word.
- Step 2: the original text comes back without the *edited* mark.
- Step 3: the lines show their new speaker; `Dana` appears as a chip.
- Step 4: the two lines become one (first line's speaker and time);
  the split gives two lines at the word boundary nearest the cursor,
  the second starting at that word's time. The dialog previews both.
- Step 5: the line disappears, then returns in place.
- Step 6: the snackbar for the delete undoes the delete only; the
  edit stays.
- Step 7: the lines whose speaker you picked keep that person (and
  `Dana` stays a speaker); the corrected line is not split or rewritten.
- Step 8: summary, answer, shared text and search all use the corrected
  text.
- Step 9: a *Discard your edits?* dialog appears first; Cancel keeps the
  transcript. After confirming, the new transcript has no *edited* marks.
- Step 10: TalkBack offers *Edit text*, *Change speaker*, *Merge with
  next*, *Split here*, *Delete line* (merge absent on the last line).
- Step 11: long-press does nothing while speakers are being identified
  (or the session is recording / re-transcribing).
- On the Boox, the menu and dialogs appear without animation and the
  *edited* mark is readable in black and white.

## 29. Summary templates (issue #27)

Run this with a working LLM endpoint, on a phone and on the **Boox**.
To check the upgrade, install over v0.5.0 without clearing data first,
using a session that already has a summary.

**Steps**

1. Open the session summarised before the upgrade, Summary tab.
2. Settings → Summaries. Change *Default template* to *Brief*, then
   go back to Home and tap Record.
3. On the Record screen, change *Summary template* to *Interview* and
   record a short two-person conversation. Open it, Summary tab →
   Summarise.
4. On the same session, pick *Brief* in the Template picker and tap
   Regenerate. Leave the session and open it again.
5. Settings → Summaries → Templates → *Duplicate* on *Meeting*. Edit
   the copy: name `Standup`, instructions `Three bullet lists: done,
   next, blockers.` Save.
6. On a session, pick `Standup` and Regenerate.
7. *New template* with an empty name or instructions.
8. Delete `Standup` (confirm), then open the session from step 6.
9. Record a conversation without touching the template, and import
   an audio file; summarise both.

**Expected**

- Step 1: the old summary is still there with no template line; the
  picker shows the current default.
- Step 2: the Record form starts on *Brief*.
- Step 3: the summary has context, questions and answers, quotes and
  follow-ups; *Written with the Interview template* above it.
- Step 4: a 3–5 bullet summary, *Written with the Brief template*;
  the picker still shows *Brief* after reopening.
- Step 5: `Standup` appears under *Your templates* with Edit,
  Duplicate and Delete; built-ins have Duplicate only. The editor shows
  the fixed opening and guardrail lines around the instructions.
- Step 6: three bullet lists in the transcript's language with the
  speakers' names; *Written with the Standup template*.
- Step 7: Save stays disabled.
- Step 8: the template is gone; the session's picker shows *Meeting*,
  and the old summary still says *Standup* until regenerated.
- Step 9: both use the default template (*Brief* from step 2).
- On the Boox, the picker menus and the editor dialog appear without
  animation and are readable in black and white.

## 30. Mark moments while recording (issue #30)

Run this on a phone and on the **Boox** (floating control in
*Minimal*), with the floating control on. To check the upgrade, install
over the previous version without clearing data first.

**Steps**

1. Open a session recorded before the upgrade.
2. Start a recording. After ~10 s tap *Mark* (right of Stop) on the
   Record screen, then *Add note* in the snackbar and type `budget`.
3. Switch to another app. Tap the flag on the floating control in each
   shape (use the arrow to change shape); on the Boox, in *Minimal*.
4. Pull down the notification and tap *Mark*.
5. Pause (anywhere), wait 10 s, tap *Mark* in the notification, then
   resume and stop.
6. Open the session. Look at the transcript and the seek bar; tap a
   flag. Tap *Marks (n)* in the player, then a mark.
7. In the Marks list, edit a note, then delete a mark (confirm).
8. Share the transcript to a notes app.
9. With a working LLM endpoint, Summary tab → Summarise.
10. Continue recording the session (mic icon), tap *Mark* once, stop.
11. Delete the session.

**Expected**

- Step 1: the session opens as before; no flags, no ticks, no
  *Marks* button, and *Marks* is disabled in the menu.
- Step 2: *Marked at 00:10* (or so); the note dialog saves `budget`.
- Step 3: each tap vibrates; the control does not redraw (on the Boox
  the screen does not flash) and the notification text gains
  *· n marks*.
- Step 4: the count in the notification goes up at once.
- Step 5: the paused mark has the paused time (the timer did not
  move).
- Step 6: each mark is a flag with its time (and note) above the line
  being spoken then, and a tick on the seek bar at that point; tapping
  a flag or a list row seeks there.
- Step 7: the note changes in the list and the transcript; the deleted
  flag and tick disappear.
- Step 8: each mark is a `[mm:ss] ⚑ Mark: note` line above its line.
- Step 9: the summary gives the noted moments (e.g. `budget`) a
  prominent place.
- Step 10: the new mark sits after the old audio, near the end of the
  seek bar; the notification counted on from the existing marks.
- Step 11: the session and its marks are gone.

## 31. Noise cleanup (issue #37)

Run this on a phone and on the **Boox**, in a noisy room (a radio or
a fan next to the phone works). To check the upgrade, install over the
previous version without clearing data first.

**Steps**

1. Open a session recorded before the upgrade: *Session info*.
2. Settings → *Noise cleanup*: look at the card, then *Download the
   cleanup model*.
3. Settings → Transcription → *Engine check* → *Check noise cleanup*.
4. Back in Settings, look at the *Noise cleanup* card again.
5. Record screen: turn *Clean up noise* on, *Set as default*, Start.
   Speak for ~30 s over the noise, pause 10 s, resume, speak, Stop.
6. Open the session. Play a few seconds; tap *Cleaned* in the player,
   then *Original*. Look at *Session info*.
7. Continue recording the session (mic icon) for 10 s, stop.
8. Record a second conversation with *Clean up noise* off. Open it,
   menu → *Clean up audio* → *Clean up*. Cancel once half-way, then run
   it again to the end.
9. Menu → *Remove cleaning* → *Remove*.
10. Settings → *Noise cleanup* → *Delete the cleanup model*. Open the
    first session and play it; menu → *Clean up audio*.
11. Delete both sessions.

**Expected**

- Step 1: *Noise cleanup: Not cleaned*; nothing else changed.
- Step 2: before the download, *Clean new recordings* is greyed out;
  the download shows progress, then *Cleanup model installed*.
- Step 3: a *Live noise cleanup* card with the cleanup real-time
  factor, the combined factor with the selected engine, and a verdict.
  The Boox is expected to say it may fall behind or is too slow.
- Step 4: the verdict line matches step 3 (a warning when live cleaning
  is not recommended).
- Step 5: the switch shows the same warning when the device is too
  slow; *Noise cleanup on* under the timer while recording and paused;
  the live transcript keeps up (no growing delay after Stop).
- Step 6: playback starts on the cleaned recording, with less
  background noise; switching keeps the position. *Session info* names
  the cleanup model.
- Step 7: the continued part is cleaned too; both versions have the
  same length and the lines still match the audio.
- Step 8: *Cleaning up audio… n%* then *Re-transcribing… n%*; Cancel
  keeps the old transcript and the session uncleaned; the second run
  replaces the transcript and adds the player toggle. Speaker names
  are kept.
- Step 9: the toggle disappears and the original plays; the transcript
  stays.
- Step 10: the cleaned session still plays its cleaned recording;
  *Clean up audio* on the other one asks to download the model in
  Settings.
- Step 11: `adb shell run-as com.convoscribe ls files/recordings` lists
  neither `<id>.wav` nor `<id>.clean.wav`.

## 32. Long sessions (issue #28)

Use a session of 20 minutes or more with a summary and a few questions
from before the upgrade; install over the previous version without
clearing data. A smaller budget makes the same session "long" without
recording for an hour.

**Steps**

1. Open the session, Ask tab: look at the old answers.
2. Settings → Language model → *Max transcript characters* `2000`, Save.
3. Summary tab → Regenerate. Watch the line next to Stop.
4. Regenerate again and tap Stop while it says *Summarising part 2 of
   …*.
5. Regenerate a third time, and turn the LLM server off (or the phone's
   Wi-Fi) half-way through the parts.
6. Ask tab: ask about something said only in the middle of the
   session. Then ask a follow-up such as "Who said that?".
7. Close and reopen the session, Ask tab.
8. Settings: set *Max transcript characters* back to `24000`. Ask a
   question in a short session (under 10 minutes).

**Expected**

- Step 1: the old answers show no *Based on …* line.
- Step 3: *Summarising part 1 of N…* up to *part N of N*, maybe
  *Combining the parts…*, then *Writing the summary…* and the summary
  streams in. It covers the beginning, the middle and the end of the
  conversation, with the selected template's layout.
- Step 4: the previous summary is still shown; nothing half-done
  replaces it.
- Step 5: an error banner with Retry; the previous summary stays.
- Step 6: the answer uses the middle of the conversation (not "not in
  the transcript"), with timestamps from there, and has a line *Based
  on X of Y minutes, the parts most relevant to the question*. The
  follow-up is answered about the same moment.
- Step 7: each answer from step 6 still shows its *Based on …* line.
- Step 8: a single summary call and answers with no *Based on …* line,
  as before the upgrade.

## 33. Notes from marks (issue #48)

Needs a working LLM endpoint and *Identify speakers* on **Auto**.
Install over the previous version without clearing data.

**Steps**

1. Open a session with marks recorded before the upgrade, Summary tab.
2. Record about two minutes with two people. Mark once with the note
   `budget` while the budget is discussed, and once without a note.
   Stop, and wait for the speakers to be identified.
3. Open the session, Summary tab, without tapping anything.
4. Rename *Speaker 1* (e.g. to `Alice`), back to the Summary tab.
5. Tap *Regenerate notes*; tap Stop while it says *Writing note 1 of 2…*.
6. Tap *Regenerate notes*, leave the session at once, and come back
   after a minute.
7. Tap a mark's time in a card.
8. Open *Marks (n)* in the player, edit the `budget` note, back to the
   Summary tab.
9. Share the transcript.
10. Turn the LLM server (or Wi-Fi) off, tap *Regenerate notes*.
11. Settings → Summaries: turn *Write notes for marks automatically*
    off. Record a short session with a mark, stop.
12. Settings → *Identify speakers* **While charging**, unplugged:
    turn the automatic notes back on and record a short session with a
    mark. Plug in, close the app, wait for the charging job, reopen.
13. On the Boox (e-ink): step 5 again, watching the screen.

**Expected**

- Step 1: the old marks are listed under *Notes from marks (n)*, each
  *Not written yet*; the old summary is unchanged.
- Step 3: both cards have a full note within a minute or so, using
  speaker names; the `budget` one is about the budget, the other says
  what was being discussed. `adb logcat -s ConvoScribe` shows
  `mark notes written session=<id> notes=2`.
- Step 4: both cards say *Outdated: … Regenerate to update.*
- Step 5: the progress line and Stop show; after Stop nothing is half
  written and the notes not yet rewritten stay as they were.
- Step 6: the notes were written while you were away, with `Alice`.
- Step 7: the Transcript tab opens with the player at the mark.
- Step 8: the edited mark's card is back to *Not written yet*; the
  Marks list shows the start of the other note under its time.
- Step 9: each `⚑ Mark` line is followed by its full note, indented.
- Step 10: an error banner with Retry and Dismiss; the notes stay.
- Step 11: the new session's card says *Not written yet*.
- Step 12: the note is written after the speakers are identified by the
  charging job.
- Step 13: each card changes once, when its note is complete; the
  screen is not redrawn word by word.

## 34. Organise conversations: tags, automatic titles, presets (issue #32)

Needs a working LLM endpoint for steps 6–9. Install over the previous
version without clearing data, with some conversations already
recorded, one of them renamed by hand.

**Steps**

1. Open Home.
2. New recording: *Tags* → type `Acme`, add it, tick it, Done. Record a
   few seconds and stop. Open another old conversation, menu → *Tags…*,
   type `acme` and `Weekly`, Done.
3. Back on Home, tick the `Acme` chip, then also `Weekly`, then untick
   both. Tick `Weekly` and, from a conversation's menu, remove its tag.
4. Search for a word from a tagged conversation and set the tag filter.
5. Settings → Organise → Tags: rename `Weekly` to `ACME`; then delete
   `Acme`.
6. Record about a minute about one topic with the title left empty,
   stop, Summary tab → Summarise, and wait.
7. Tap *Undo* on the snackbar. Summarise again.
8. Do step 6 again, leave the session as soon as the summary finishes,
   and come back. Menu → *Restore “Recording …”*.
9. Summarise the conversation renamed by hand before the upgrade, and an
   imported one. Then Settings → Summaries: turn *Name conversations
   automatically* off and do step 6 again.
10. On the Record screen set French, 3 speakers, a participant, title
    `Weekly sync {date} {time}`, tag `Acme`, template *Interview*.
    *Save as preset…* → `Weekly`. Leave and come back to the Record
    screen; pick *Preset* → `Weekly`, change the speaker count, then
    pick `Weekly` again. Record and stop.
11. Settings → Organise → Presets: edit `Weekly` (rename it, change the
    tags), add a new preset, delete one.
12. Long-press the Quick Settings tile; pick `Weekly`. Stop from the
    notification. Long-press it again while a recording is open.
13. Revoke the microphone permission in system settings, long-press the
    tile and pick `Weekly`.
14. On the Boox (e-ink): steps 1–3 and 10.

**Expected**

- Step 1: the conversation renamed by hand and imported ones keep their
  titles; nothing else looks different; no tag chips yet.
- Step 2: both conversations show `Acme` (one tag, first spelling kept)
  on their cards and under the header; the second also `Weekly`.
- Step 3: the list shows only the `Acme` conversations, then those with
  either tag, then all. A filter that matches nothing shows *No
  conversations with these tags* with *Clear filter*; a tag that is on
  no conversation disappears from the chips.
- Step 4: only conversations with that tag are in the results.
- Step 5: the rename merges the two (one `Acme`-named tag with both
  conversations); after the delete the conversations have no tags and
  are still there.
- Step 6: a few seconds after the summary the title changes to a short
  topic title (at most six words, in the conversation's language) and
  a snackbar *Renamed to “…”* offers *Undo*. `adb logcat -s AutoTitler`
  shows `auto title session=<id>`.
- Step 7: the date title is back and the second summary does not
  rename it.
- Step 8: the title changed while you were away; the menu restores the
  date title.
- Step 9: none of these titles change.
- Step 10: the new conversation is titled `Weekly sync 2026-… …` with
  today's date and time, tagged `Acme`, in French with the Interview
  template. *Preset* shows `Weekly` until an option is changed, then
  *None*.
- Step 11: the list shows the changes; the Record screen's *Preset*
  picker offers the same presets.
- Step 12: the picker lists *Settings defaults* and the presets; picking
  one starts recording at once (notification) with its options. With a
  recording open, the long-press opens the Record screen instead.
- Step 13: the Record screen opens with the preset applied and asks for
  the permission on Start.
- Step 14: chips and dialogs are outlined, black on white, and redraw
  once per change.

## 35. Voice model and analysis step (issue #56)

Install over the previous version without clearing data, with at least
one enrolled known speaker and a recorded conversation of a few minutes
with two or more people.

**Steps**

1. Open an older conversation → menu → *Session info*.
2. Settings → Speakers. Check *Voice model* and *Analysis step*.
3. On that conversation, *Re-identify speakers…* → Automatic. Open
   *Session info*.
4. Settings → Speakers → *Voice model* → CAM++. Re-identify the same
   conversation before downloading it, then open *Session info*.
5. Tap *Download CAM++*; wait for it to finish. Re-identify again and
   open *Session info*.
6. Set *Analysis step* to 3 s. Re-identify again and open *Session
   info*. Compare the speakers with step 3.
7. Record a short conversation with the enrolled person speaking.
8. Set *Voice model* back to ResNet34 and *Analysis step* to 1 s.
9. On the Boox (e-ink): step 2.

**Expected**

- Step 1: *Speaker identification* shows `unknown`.
- Step 2: ResNet34 and 1 s are selected on an existing install, as on a
  fresh one.
- Step 3: *Speaker identification* reads `ResNet34, 1 s step, took
  mm:ss (… × real time)`. `adb logcat -s ConvoScribe` shows `diarizer
  embedding=emb-wespeaker-resnet34-lm stepS=1.0` and a `diarization
  engine … rtf=…` line.
- Step 4: the section says ResNet34 is used until CAM++ is downloaded
  and offers *Download CAM++ (29.3 MB)*. The run still succeeds and
  Session info says ResNet34; logcat shows `voice model
  emb-wespeaker-campplus-lm not downloaded`.
- Step 5: while downloading the section shows *Downloading CAM++…*,
  then the button goes. Session info says CAM++, with a shorter time
  than step 3.
- Step 6: Session info says `3 s step`, with a shorter time than
  step 5. Speakers may differ slightly at short turns.
- Step 7: the enrolled person is still named (known voices use
  ResNet34 whatever the setting).
- Step 8: the next identification is the same as in step 3.
- Step 9: the choices are outlined, black on white.

## 36. Transcription server (issue #58)

Needs a reachable OpenAI-compatible transcription server, e.g. the
Parakeet server at `http://parakeet-asr.asr:8000/v1` from a device on
the cluster network (or an `https://` ingress in front of it), and a
recorded English conversation of a few minutes with two people.

**Steps**

1. Settings → *Transcription server*. Leave the address blank: the
   *Improve recordings on the server* switch is greyed out.
2. Enter `http://parakeet-asr.asr:8000/v1`, leave model blank, tap
   *Test connection*.
3. Enter an address that does not answer (e.g.
   `https://192.0.2.1/v1`), tap *Test connection*, then put the working
   one back.
4. Open the recorded conversation → menu → *Re-transcribe…*. Choose
   *On the transcription server* and confirm.
5. Repeat step 4 and tap *Cancel* on the progress banner while it runs.
6. *Session info* after step 4.
7. Turn on *Improve recordings on the server*. Record a short
   conversation and stop.
8. Edit a line of that conversation, tap *Continue recording*, say a
   few words and stop.
9. Turn on aeroplane mode (or stop the server), record a few seconds
   and stop.
10. With the server reachable again, *Import audio…* a voice note.
11. Turn *Improve recordings on the server* off, record and stop.

**Expected**

- Step 2: a warning under the address says recordings are sent
  unencrypted; the test reports `Connected – 1 model:
  parakeet-tdt-0.6b-v3`. Tapping the chip fills the model.
- Step 3: *Failed: Network error calling …*. Nothing else changes.
- Step 4: the dialog shows *Transcribe: On this device / On the
  transcription server* above the languages; picking the server
  selects English. The banner and notification (*Transcribing on the
  server*) show progress, which stops at 90 % while the server works.
  The transcript is replaced with punctuated, capitalised lines, and
  speakers are identified again, with the names kept. Playback still
  highlights words.
- Step 5: the previous transcript stays; no error.
- Step 6: the engine reads `server`, the model `parakeet-tdt-0.6b-v3`.
- Step 7: after Stop the notification says *Transcribing on the
  server*, then *Identifying speakers*; the transcript ends up
  punctuated and the engine is `server`. Cancel on the banner keeps
  the live transcript and speakers are still identified.
- Step 8: no server pass after Stop (the transcript was edited); the
  edit is kept.
- Step 9: after a short wait the live transcript is kept without an
  error, and speakers are identified. `adb logcat -s ConvoScribe` shows
  `re-transcription failed … (server=true)`.
- Step 10: the import is transcribed on the server (Session info:
  `server`). Stopping the server first falls back to the device engine.
- Step 11: no server pass; the engine is `cpu` or `npu`.

## 37. Ask across sessions (issue #49)

Run this on a phone and on the **Boox**, with a language model endpoint
set up in Settings. You need several sessions: a fact said in only one
**older** session (e.g. a name or a project), a French and an English
session, a renamed speaker (e.g. *Élodie*), a tagged session and a
session with a saved summary. To check the upgrade, install this build
**over** v0.13.0 with those sessions recorded, without clearing data.

**Steps**

1. On Home, tap the speech-bubble icon in the top bar. Go back, open
   Search and tap the same icon there.
2. Ask about the fact said only in the older session.
3. Tap each citation chip under the answer.
4. Ask a follow-up that does not repeat the topic (*Who said that?*).
5. Choose *Français* in the language chip and ask a question; then set
   it back and choose the renamed speaker in the person chip, then a
   tag, then *Last 7 days*.
6. Ask something none of the sessions talks about.
7. Ask a question and tap Stop while the answer streams; then turn
   Wi-Fi off, ask again, turn it back on and tap *Retry*.
8. Delete a session that an answer cites, and come back to the screen.
9. Leave the screen and come back; then menu → *Clear history*.
10. On the Boox, ask a question that gets a long answer and watch the
    screen; do the same on a session's Ask tab.

**Expected**

- Step 1: *Ask across sessions* opens, with the filter chips at the top
  and an empty state explaining it.
- Step 2: the answer states the fact, cites the older session as
  *(Title, yyyy-mm-dd, mm:ss)* in the text, and shows a chip
  *Title · yyyy-mm-dd · mm:ss* for it. Under the answer: *Based on N
  excerpts from M sessions*.
- Step 3: the session opens on the Transcript tab, scrolled to the cited
  line, with the player cued there and **not playing**.
- Step 4: the answer still knows what the previous question was about.
- Step 5: only sessions passing the filters are used (the citations show
  it); with a person chosen, lines by that person are preferred.
- Step 6: the answer says it is not in the conversations it was given;
  no invented facts.
- Step 7: Stop keeps the question and saves no answer; without network
  an error banner shows *Retry*, which answers with the filters the
  question was asked with.
- Step 8: the answer is still there; its chip for the deleted session
  reads *Session deleted · …* and cannot be tapped.
- Step 9: the questions and answers are still there after coming back;
  *Clear history* asks first, then empties the screen. Sessions and the
  session Ask tabs are untouched.
- Step 10: the answer appears in steps about once a second rather than
  word by word, on both screens; the phone still streams word by word.
- After the upgrade from v0.13.0, existing sessions are found and the
  history starts empty.

## 38. Semantic search (issue #61)

Run this on the **Boox** (CPU only) and on a phone, with a language
model endpoint set up and the sessions from test 37. One **older**
session should say a fact in words a question would not use (e.g. *the
budget is capped at ten thousand euros*), and a French session should
hold a fact you will ask about in English. Install this build over the
previous one without clearing data.

**Steps**

1. Open Ask across sessions and ask *How much money can we spend?*
   Note the answer.
2. Settings → Semantic search: download the model. Watch the section
   while it downloads, and after.
3. Engine check → *Check semantic search*. Note the numbers.
4. Unplug, then plug in the charger with the screen off for ten
   minutes. Open Settings → Semantic search.
5. Open Ask across sessions and ask the question from step 1 again.
   Then ask in English about the fact said in French.
6. Edit a line of an indexed session (Transcript tab) so it states a
   new fact in other words. Charge again for a few minutes and ask
   about it.
7. Record a short new session and open Ask across sessions before
   charging.
8. On battery, open that session's menu → *Index for semantic search*.
   Open the menu again while it runs, and once it is done.
9. Record two more short sessions. Settings → Semantic search → **Index
   now**, on battery. Leave Settings and come back after a minute.
10. *Index sessions* → **Any time**. Record a short session, close the
    app and wait a few minutes on battery
    (`adb shell dumpsys jobscheduler | grep -A5 com.convoscribe` shows
    the job's constraints). Switch back to **While charging**.
11. Settings → Semantic search → *Delete the semantic search model*,
    then ask the question from step 1 again.

**Expected**

- Step 1: without the model, the older session is missed, or found
  only if a word matches; the answer has no *matched by meaning*.
- Step 2: a progress row, then *Index sessions* (**While charging**
  selected), *0 of N sessions indexed. Indexing runs while the device
  is charging.*, **Index now** and a delete button. The help text
  names the model's size and the Gemma licence line shows.
- Step 3: *Load the model*, *Index one passage*, *Read one question*,
  *App memory before* and *App memory at its peak* show; no error. On
  the Boox, note the numbers in the issue: one question should take
  well under a second, and the peak memory should not make the app
  close.
- Step 4: the count goes up (all sessions after enough charging). The
  phone stays responsive, and nothing runs while recording.
- Step 5: the older session's fact is cited, and the answer reads
  *Based on N excerpts from M sessions · matched by meaning*. The
  English question cites the French session.
- Step 6: after charging, the new wording is found.
- Step 7: a line under the filters says *1 session is not indexed for
  semantic search yet…*; questions still work, by words for that
  session.
- Step 8: the entry reads *Indexing for semantic search…* (disabled)
  while it runs, then disappears; Ask across sessions no longer counts
  the session as not indexed. During a recording or speaker
  identification, a message says the app is busy instead.
- Step 9: the count reaches the total without charging; **Index now**
  then disappears.
- Step 10: the job waits for *battery not low* rather than charging, and
  the session is indexed on battery. The Settings and Ask across texts
  say indexing runs whenever the battery is not low; switching back
  restores the charging wording and constraint.
- Step 11: the section offers the download again, the count disappears,
  and the answer is word-based as in step 1, without *matched by
  meaning*.

## 39. Back up and restore (issue #25)

Run this on a phone with at least three sessions: one diarized with a
renamed speaker, a summary, a question, a mark with a note and a tag;
one cleaned (noise cleanup); one imported. Have a known voice, a user
summary template (set as the default) and a preset. Set an LLM API key.
Install this build over the previous one without clearing data: the
sessions must still be there (database v16).

**Steps**

1. Settings → Data → **Back up sessions…**, save to Downloads. Leave
   the app while it runs (pull down the notification).
2. Copy the file to a computer: `adb pull /sdcard/Download/convoscribe-backup-*.zip`.
   `unzip -l` it, and `unzip -p … backup.json | grep -i sk-` (use part
   of your API key).
3. Start a backup again and tap **Cancel** in the notification.
4. Uninstall the app, reinstall this build and go through setup.
   Settings → Data → **Restore from backup…**, pick the file, leave
   *Also restore settings* ticked, **Restore**.
5. Open each restored session: play it, read the transcript and its
   speakers, the Summary and Ask tabs, the marks, the tags. Check
   Settings (LLM URL and model, default template), Settings → Speakers
   → known voices, and Presets.
6. Restore the same file again.
7. Start a restore and **Cancel** it halfway (on a backup with several
   long sessions). Then restore again.
8. Try to restore a file that is not a backup (any other zip or a
   photo).
9. Start a recording, then open Settings → Data → **Back up sessions…**.

**Expected**

- Step 1: a notification *Backing up sessions* with a percentage and
  Cancel, then *Backup saved — N sessions saved.*; Settings shows the
  same. A session still recording is not counted.
- Step 2: `backup.json` first, then `sessions/<uuid>.json` each
  followed by its `recordings/<sha256>.wav` (two for the cleaned
  session). No API key appears anywhere.
- Step 3: the notification goes away with no result, and no partial
  file is left in Downloads.
- Step 4: *Restoring sessions* with progress, then *Restore complete —
  N sessions restored.*
- Step 5: every session is as it was, including word-level highlight
  during playback, speaker names and colours, the cleaned recording, and
  the session's template. Settings, known voices, template and preset
  are back; the API key is empty and must be typed again. Sessions
  waiting for speakers get them from the charging job.
- Step 6: *0 sessions restored. N were already on this device.*;
  nothing is duplicated.
- Step 7: the cancelled restore leaves only complete sessions (each
  plays and has its transcript); restoring again adds the rest.
- Step 8: *Restore failed — This file is not a ConvoScribe backup.*;
  nothing changes.
- Step 9: a message asks to wait for the recording to finish; no file
  picker opens.

## Troubleshooting

### 1. QNN errors (DSP not reachable)

QNN errors with the code `14001` mean the engine cannot reach the
Hexagon DSP. To confirm:

```bash
adb logcat -s sherpa-onnx ConvoScribe AndroidRuntime | head -100
```

Check `uses-native-library` is present in the manifest:

```bash
grep uses-native-library app/src/main/AndroidManifest.xml
# should print: <uses-native-library android:name="libcdsprpc.so" android:required="false"/>
```

Check the userspace library actually exists on the device:

```bash
adb shell ls -l /vendor/lib64/libcdsprpc.so
```

If the file is missing, the Q-6690 BSP is incomplete — install the
vendor image and retry.

If the error persists, force the engine to re-prepare the QNN
context binaries by deleting them (they will be re-generated on the
next NPU run; this takes minutes):

```bash
adb shell run-as com.convoscribe ls files/models/asr-en-nemotron-0.6b-560ms-qnn
# delete the .bin files only; keep the .so files
adb shell run-as com.convoscribe rm \
    files/models/asr-en-nemotron-0.6b-560ms-qnn/encoder.bin \
    files/models/asr-en-nemotron-0.6b-560ms-qnn/decoder.bin \
    files/models/asr-en-nemotron-0.6b-560ms-qnn/joiner.bin
```

### 2. Where models live

```bash
adb shell run-as com.convoscribe ls -l files/models/
# sub-folders: asr-en-kroko-2025-08-06, asr-fr-kroko-2025-08-06,
#              seg-pyannote-3-0-int8, emb-wespeaker-resnet34-lm,
#              asr-en-nemotron-0.6b-560ms-qnn
```

Delete any of those sub-folders to redownload from scratch. The NPU
folder also caches `encoder.bin`, `decoder.bin`, `joiner.bin` next
to the shared libraries — wiping those forces a re-prepare.

### 3. Cleartext traffic denied

Some local LLM servers (Ollama out of the box) and transcription
servers answer on plain HTTP.
`usesCleartextTraffic="true"` is already set on the `<application>`
in the manifest, so `http://<host>:11434/v1` is allowed. If you
have placed a stricter `networkSecurityConfig` XML on the app, add
an exception for the local subnet.

### 4. Clear app data

When the only sane option is a fresh start (DB corrupt, settings
wedged, model checksums stale):

```bash
adb shell pm clear com.convoscribe
```

This wipes the database, the recordings, the downloaded models and
the SharedPreferences in one shot. You will need to redownload the
model.

### 5. Storage paths (for verification)

```bash
adb shell run-as com.convoscribe find files -type f | head
```

Expect:

```
files/recordings/<sessionId>.wav
files/models/<bundleId>/<file>
files/models/<bundleId>/<archive-extracted-dir>/…
files/convoscribe.db
files/convoscribe.db-shm
files/convoscribe.db-wal
```

### 6. Capturing a useful bug report

For a single-record bug, one command gathers everything:

```bash
adb logcat -d -s ConvoScribe:I sherpa-onnx:I AndroidRuntime:E \
    > convoscribe-$(date +%Y%m%d-%H%M%S).log
adb shell run-as com.convoscribe tar -cf - files \
    | tar -xf - -C convoscribe-$(date +%Y%m%d-%H%M%S)-files
```

Attach both to the issue.
