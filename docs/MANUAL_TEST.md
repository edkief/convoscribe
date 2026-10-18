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
without regenerating.

## 11. Ask questions (context)

**Steps**

1. In the same session, open the **Ask** tab.
2. Ask "When did they say they would follow up?"
3. Ask "Who said that?" (a follow-up that needs prior context).

**Expected** — both questions stream token by token; the second
answer uses the first question's answer / the transcript as
context (no "I don't have context for this question" error).

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

1. Open an English session with a summary, overflow → **Re-transcribe
   in another language…**, pick Français (download it first if asked),
   confirm.
2. Watch the banner and notification progress; leave the app.
3. Repeat on another session and tap **Cancel** halfway (banner or
   notification).

**Expected** — the dialog warns that the summary and questions were
based on the old transcript. Step 1 replaces the transcript with a
French one, speakers are identified again, and names you typed are
kept. After the cancel in step 3 the previous transcript, language
and speakers are unchanged. **Start** on the Record screen is
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
  Settings language and the engine.
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

The automatic check runs at most once a day. To make it run again on
the next start:

```bash
adb shell run-as com.convoscribe rm shared_prefs/updates.xml
adb shell am force-stop com.convoscribe
```

Filter logcat with `adb logcat -s ConvoScribe:I` (lines start with
`update check:` / `update install status=`).

**Steps**

1. Start ConvoScribe (old build). Within a few seconds Home shows
   *ConvoScribe X.Y.Z is available*. Tap **View notes**: the release
   notes appear; close them.
2. Tap **Not now**: the banner goes. Settings → About shows
   *Version 0.0.1 (1)* and the same offer. Force-stop and reopen: no
   banner (checked less than a day ago).
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

Some local LLM servers (Ollama out of the box) answer on plain HTTP.
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
