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
   appears; tap **Download**.
2. Observe the progress bar; on a slow link it takes ~5–10 minutes for
   the NPU bundle (~450 MB compressed body, ≈610 MB extracted).
3. Mid-download, swipe down and turn Wi-Fi off for ~10 s, then turn
   it back on. Watch the bar.

**Expected** — the progress bar freezes while Wi-Fi is down, then
resumes without rebasing below the previously-reached byte count
(no "start over" jump); once the bar reaches the total the screen
pops to **Home**. The logcat line below appears once when the
download completes:

```
I ConvoScribe(12345): model download completed bytesDone=… bundles=…
```

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
