#!/usr/bin/env python3
"""Score ConvoScribe's speaker attribution on hand-labelled recordings.

Runs the same models and settings as the app, on the desktop:

  * streaming Kroko ASR with the app's endpoint rules and 100 ms chunks
    (EndpointSegmenter / SherpaAsrConfigs), keeping per-token times;
  * pyannote segmentation + WeSpeaker embeddings + fast clustering
    (SherpaDiarizer), swept over clustering threshold and speaker count;
  * two ways of labelling the transcript:
      - "line":  one speaker per transcript line by largest overlap
                 (a port of SpeakerAssigner, the behaviour before #1);
      - "split": lines split at speaker changes from token times
                 (a port of SegmentSplitter).

The score is word-level speaker attribution accuracy: the share of
recognised words whose predicted speaker matches the labelled speaker
at that moment, after the best one-to-one mapping between predicted
and labelled speakers.

Labels are an Audacity label export (start<TAB>end<TAB>speaker, in
seconds) or a CSV with start,end,speaker columns.

Keep the ported logic in sync with the Kotlin it mirrors:
  app/src/main/java/com/convoscribe/diarization/SpeakerAssigner.kt
  app/src/main/java/com/convoscribe/diarization/SegmentSplitter.kt
"""

from __future__ import annotations

import argparse
import csv
import itertools
import os
import sys
import urllib.request
import wave
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import sherpa_onnx as so

SAMPLE_RATE = 16_000
CHUNK_SAMPLES = 1_600  # AudioSpec.CHUNK_SAMPLES
NEAREST_GAP_MS = 1_000  # SpeakerAssigner.NEAREST_GAP_MS
MIN_RUN_MS = 300  # SegmentSplitter.MIN_RUN_MS
DEFAULT_THRESHOLD = 0.75  # SettingsRepository.DEFAULT_CLUSTER_THRESHOLD

HF = "https://huggingface.co/csukuangfj"
# Same files as app/src/main/java/com/convoscribe/models/ModelCatalog.kt.
ASR_BASE = {
    "en": f"{HF}/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/",
    "fr": f"{HF}/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/",
}
SEGMENTATION_URL = f"{HF}/sherpa-onnx-pyannote-segmentation-3-0/resolve/main/model.int8.onnx"
SPEAKER_MODELS = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/"
# Speaker identification voice models, by the app's
# AppSettings.diarizationEmbedding key (ModelCatalog.embeddingFor);
# resnet34 is the default. A few more are listed to compare with.
EMBEDDINGS = {
    "resnet34": "wespeaker_en_voxceleb_resnet34_LM.onnx",
    "campplus": "wespeaker_en_voxceleb_CAM++_LM.onnx",
    "campplus-3d": "3dspeaker_speech_campplus_sv_en_voxceleb_16k.onnx",
    "eres2net-3d": "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
    "titanet-small": "nemo_en_titanet_small.onnx",
}
SEGMENTATION_WINDOW_S = 10.0  # pyannote-3.0's window; the step is a share of it
DEFAULT_STEP_S = 1.0  # AppSettings.diarizationStepSeconds
# Two LibriSpeech speakers, for --make-demo.
DEMO_CLIPS = [
    f"{HF}/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/test_wavs/0.wav",
    f"{HF}/sherpa-onnx-streaming-zipformer-en-2023-06-26/resolve/main/test_wavs/1.wav",
]


# ---------------------------------------------------------------- data


@dataclass
class Token:
    text: str
    ms: int  # absolute time in the recording


@dataclass
class Segment:  # one transcript line, as EndpointSegmenter emits it
    start_ms: int
    end_ms: int
    text: str
    tokens: list[Token]


@dataclass
class Turn:  # one diarizer turn
    start_ms: int
    end_ms: int
    speaker: int
    confidence: float


# ------------------------------------------------------------- helpers


def cache_dir() -> Path:
    d = Path(os.environ.get("CONVOSCRIBE_EVAL_CACHE", Path.home() / ".cache" / "convoscribe-eval"))
    d.mkdir(parents=True, exist_ok=True)
    return d


def fetch(url: str, dest: Path) -> Path:
    if not dest.exists():
        dest.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {url}", file=sys.stderr)
        tmp = dest.with_suffix(dest.suffix + ".part")
        urllib.request.urlretrieve(url, tmp)
        tmp.rename(dest)
    return dest


def models(lang: str, embedding: str = "resnet34") -> dict[str, Path]:
    root = cache_dir()
    asr = root / f"kroko-{lang}"
    out = {name: fetch(ASR_BASE[lang] + name, asr / name)
           for name in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt")}
    out["segmentation"] = fetch(SEGMENTATION_URL, root / "pyannote-segmentation-3-0.int8.onnx")
    name = EMBEDDINGS[embedding]
    out["embedding"] = fetch(SPEAKER_MODELS + name.replace("+", "%2B"), root / name)
    return out


def read_wav(path: Path) -> np.ndarray:
    """Mono 16-bit PCM WAV as float32 at 16 kHz (resampled like WavReader)."""
    with wave.open(str(path)) as w:
        if w.getnchannels() != 1 or w.getsampwidth() != 2:
            sys.exit(f"{path}: need mono 16-bit PCM")
        rate = w.getframerate()
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    if rate != SAMPLE_RATE:
        n = int(len(x) * SAMPLE_RATE / rate)
        x = np.interp(np.arange(n) * rate / SAMPLE_RATE, np.arange(len(x)), x).astype(np.float32)
    return x


def write_wav(path: Path, x: np.ndarray) -> None:
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SAMPLE_RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype(np.int16).tobytes())


def read_labels(path: Path) -> list[tuple[int, int, str]]:
    """Audacity labels (start\\tend\\tname), CSV start,end,speaker, or RTTM; seconds."""
    rows = []
    text = path.read_text()
    if text.startswith("SPEAKER "):  # RTTM: SPEAKER file chan start dur <NA> <NA> name ...
        for line in text.splitlines():
            f = line.split()
            if len(f) >= 8 and f[0] == "SPEAKER":
                start, dur = float(f[3]), float(f[4])
                rows.append((int(start * 1000), int((start + dur) * 1000), f[7]))
        return sorted(rows)
    delim = "\t" if "\t" in text else ","
    for row in csv.reader(text.splitlines(), delimiter=delim):
        if len(row) < 3 or not row[0].strip():
            continue
        try:
            start, end = float(row[0]), float(row[1])
        except ValueError:
            continue  # header row
        rows.append((int(start * 1000), int(end * 1000), row[2].strip()))
    if not rows:
        sys.exit(f"{path}: no labels found")
    return rows


def is_word_start(token: str) -> bool:
    # sherpa-onnx returns sentencepiece word starts as a leading space;
    # accept the raw "▁" too.
    return token.startswith(" ") or token.startswith("▁")


# ------------------------------------------------------------------ ASR


def transcribe(audio: np.ndarray, m: dict[str, Path], threads: int) -> list[Segment]:
    """Mirror of EndpointSegmenter driven like RecordingController."""
    rec = so.OnlineRecognizer.from_transducer(
        tokens=str(m["tokens.txt"]), encoder=str(m["encoder.onnx"]),
        decoder=str(m["decoder.onnx"]), joiner=str(m["joiner.onnx"]),
        num_threads=threads, sample_rate=SAMPLE_RATE, feature_dim=80,
        enable_endpoint_detection=True,
        # SherpaAsrConfigs.endpoint
        rule1_min_trailing_silence=2.4, rule2_min_trailing_silence=0.8,
        rule3_min_utterance_length=20.0,
        decoding_method="greedy_search", model_type="zipformer2",
    )
    stream = rec.create_stream()
    segments: list[Segment] = []
    seg_start = 0
    fed = 0

    def emit(end_sample: int) -> None:
        r = rec.get_result_all(stream)
        if not r.text.strip():
            return
        # Token times restart at 0 after each reset: add the line's start.
        offset_ms = seg_start * 1000 // SAMPLE_RATE
        toks = [Token(t, offset_ms + int(ts * 1000)) for t, ts in zip(r.tokens, r.timestamps)]
        segments.append(Segment(offset_ms, end_sample * 1000 // SAMPLE_RATE, " ".join(r.text.split()), toks))

    for i in range(0, len(audio), CHUNK_SAMPLES):
        chunk = audio[i:i + CHUNK_SAMPLES]
        stream.accept_waveform(SAMPLE_RATE, chunk)
        fed += len(chunk)
        while rec.is_ready(stream):
            rec.decode_stream(stream)
        if rec.is_endpoint(stream):
            emit(fed)
            rec.reset(stream)
            seg_start = fed
    stream.input_finished()
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    emit(fed)
    return segments


# ---------------------------------------------------------- diarization


@dataclass
class Diarizer:  # the Python binding has set_config but no getter
    sd: so.OfflineSpeakerDiarization
    config: so.OfflineSpeakerDiarizationConfig


def make_diarizer(m: dict[str, Path], threads: int, step: float = DEFAULT_STEP_S) -> Diarizer:
    cfg = so.OfflineSpeakerDiarizationConfig(
        segmentation=so.OfflineSpeakerSegmentationModelConfig(
            pyannote=so.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(m["segmentation"]), window_shift_ratio=step / SEGMENTATION_WINDOW_S),
            num_threads=threads,
        ),
        embedding=so.SpeakerEmbeddingExtractorConfig(model=str(m["embedding"]), num_threads=threads),
        clustering=so.FastClusteringConfig(num_clusters=-1, threshold=DEFAULT_THRESHOLD, compute_confidence=True),
        # SherpaDiarizer
        min_duration_on=0.3,
        min_duration_off=0.5,
    )
    return Diarizer(so.OfflineSpeakerDiarization(cfg), cfg)


def diarize(d: Diarizer, audio: np.ndarray, num_speakers: int, threshold: float) -> list[Turn]:
    d.config.clustering = so.FastClusteringConfig(
        num_clusters=num_speakers, threshold=threshold, compute_confidence=True)
    d.sd.set_config(d.config)
    result = d.sd.process(audio).sort_by_start_time()
    return [Turn(int(s.start * 1000), int(s.end * 1000), s.speaker, float(getattr(s, "confidence", 1.0)))
            for s in result]


# ------------------------------------------------- labelling strategies


def overlap(a0: int, a1: int, b0: int, b1: int) -> int:
    return max(0, min(a1, b1) - max(a0, b0))


def gap(a0: int, a1: int, b0: int, b1: int) -> int:
    if a1 <= b0:
        return b0 - a1
    if b1 <= a0:
        return a0 - b1
    return 0


def assign_line(start_ms: int, end_ms: int, turns: list[Turn]) -> int | None:
    """SpeakerAssigner.assignRaw: largest total overlap, else nearest turn within 1 s."""
    totals: dict[int, int] = {}
    earliest: dict[int, int] = {}
    for t in turns:
        o = overlap(start_ms, end_ms, t.start_ms, t.end_ms)
        if o > 0:
            totals[t.speaker] = totals.get(t.speaker, 0) + o
            earliest[t.speaker] = min(earliest.get(t.speaker, t.start_ms), t.start_ms)
    if totals:
        return max(totals, key=lambda s: (totals[s], -earliest[s]))
    best = min(turns, key=lambda t: (gap(start_ms, end_ms, t.start_ms, t.end_ms), t.start_ms), default=None)
    if best is not None and gap(start_ms, end_ms, best.start_ms, best.end_ms) <= NEAREST_GAP_MS:
        return best.speaker
    return None


def speaker_at(ms: int, turns: list[Turn]) -> int | None:
    """SegmentSplitter: the turn containing ms, else the nearest within 1 s."""
    for t in turns:
        if t.start_ms <= ms < t.end_ms:
            return t.speaker
    best = min(turns, key=lambda t: (gap(ms, ms, t.start_ms, t.end_ms), t.start_ms), default=None)
    if best is not None and gap(ms, ms, best.start_ms, best.end_ms) <= NEAREST_GAP_MS:
        return best.speaker
    return None


def words_of(seg: Segment) -> list[list[Token]]:
    words: list[list[Token]] = []
    for tok in seg.tokens:
        if not words or is_word_start(tok.text):
            words.append([tok])
        else:
            words[-1].append(tok)
    return words


def split_line(seg: Segment, turns: list[Turn]) -> list[int | None]:
    """SegmentSplitter: one speaker per word of the line, after smoothing."""
    words = words_of(seg)
    speakers = [speaker_at(w[0].ms, turns) for w in words]
    # Words between turns take a neighbour's speaker.
    for i in range(1, len(speakers)):
        if speakers[i] is None:
            speakers[i] = speakers[i - 1]
    for i in range(len(speakers) - 2, -1, -1):
        if speakers[i] is None:
            speakers[i] = speakers[i + 1]
    # Merge runs shorter than MIN_RUN_MS into the previous run (or the next).
    changed = True
    while changed:
        changed = False
        runs = []  # [first word index, last word index]
        for i, s in enumerate(speakers):
            if runs and speakers[runs[-1][0]] == s:
                runs[-1][1] = i
            else:
                runs.append([i, i])
        if len(runs) < 2:
            break
        for r, (a, b) in enumerate(runs):
            start = words[a][0].ms
            end = words[b + 1][0].ms if b + 1 < len(words) else seg.end_ms
            if end - start < MIN_RUN_MS:
                target = speakers[runs[r - 1][0]] if r > 0 else speakers[runs[r + 1][0]]
                for i in range(a, b + 1):
                    speakers[i] = target
                changed = True
                break
    return speakers


# --------------------------------------------------------------- scoring


def truth_at(ms: int, labels: list[tuple[int, int, str]]) -> str | None:
    for start, end, name in labels:
        if start <= ms < end:
            return name
    near = min(labels, key=lambda r: gap(ms, ms, r[0], r[1]))
    return near[2] if gap(ms, ms, near[0], near[1]) <= 500 else None


def accuracy(pairs: list[tuple[int | None, str]]) -> float:
    """Share of words right under the best one-to-one predicted→labelled mapping."""
    scored = [(p, t) for p, t in pairs if t is not None]
    if not scored:
        return float("nan")
    preds = sorted({p for p, _ in scored if p is not None})
    truths = sorted({t for _, t in scored})
    counts = {(p, t): 0 for p in preds for t in truths}
    for p, t in scored:
        if p is not None:
            counts[(p, t)] += 1
    best = 0
    if len(preds) <= 7:
        k = min(len(preds), len(truths))
        for ps in itertools.permutations(preds, k):
            for ts in itertools.combinations(truths, k):
                for perm in itertools.permutations(ts):
                    best = max(best, sum(counts[(p, t)] for p, t in zip(ps, perm)))
    else:  # greedy for many speakers
        used_p, used_t = set(), set()
        for (p, t), c in sorted(counts.items(), key=lambda kv: -kv[1]):
            if p not in used_p and t not in used_t:
                best += c
                used_p.add(p)
                used_t.add(t)
    return best / len(scored)


def time_accuracy(turns: list[Turn], labels, step_ms: int = 50) -> float:
    """Share of single-speaker labelled time given to the right speaker.

    Like `accuracy`, but per 50 ms frame instead of per recognised word,
    so it needs no ASR. Overlapped speech is skipped; frames the diarizer
    left unassigned count as wrong.
    """
    end = max(e for _, e, _ in labels)
    n = end // step_ms + 1
    truth = np.full(n, -1, dtype=np.int32)
    names = sorted({name for _, _, name in labels})
    for s, e, name in labels:
        a, b = s // step_ms, e // step_ms
        seg = truth[a:b]
        seg[seg == -1] = names.index(name)
        seg[(seg != -1) & (seg != names.index(name))] = -2  # overlap
    pred = np.full(n, -1, dtype=np.int32)
    for t in turns:
        a, b = t.start_ms // step_ms, min(n, t.end_ms // step_ms)
        seg = pred[a:b]
        seg[seg == -1] = t.speaker
    keep = truth >= 0
    pairs = [(int(p) if p >= 0 else None, names[int(t)]) for p, t in zip(pred[keep], truth[keep])]
    return accuracy(pairs)


def cluster_f1(turns: list[Turn], labels, step_ms: int = 50) -> float:
    """Harmonic mean of cluster purity and coverage over single-speaker frames.

    Purity: how much of each detected speaker is one real person.
    Coverage: how much of each real person lands in one detected speaker.
    Too many speakers lowers coverage, too few lowers purity, so unlike
    `time_accuracy` it doesn't reward lumping a quiet speaker in with a
    dominant one. Unassigned frames count against coverage.
    """
    end = max(e for _, e, _ in labels)
    n = end // step_ms + 1
    names = sorted({name for _, _, name in labels})
    truth = np.full(n, -1, dtype=np.int32)
    for s, e, name in labels:
        seg = truth[s // step_ms:e // step_ms]
        k = names.index(name)
        seg[(seg != -1) & (seg != k)] = -2
        seg[seg == -1] = k
    pred = np.full(n, -1, dtype=np.int32)
    for t in turns:
        seg = pred[t.start_ms // step_ms:min(n, t.end_ms // step_ms)]
        seg[seg == -1] = t.speaker
    keep = truth >= 0
    tr, pr = truth[keep], pred[keep]
    if len(tr) == 0:
        return float("nan")
    assigned = pr >= 0
    purity = 0.0
    if assigned.any():
        purity = sum(np.bincount(tr[assigned & (pr == p)]).max() for p in np.unique(pr[assigned])) / assigned.sum()
    coverage = sum(np.bincount(pr[(tr == t) & assigned]).max() if ((tr == t) & assigned).any() else 0
                   for t in np.unique(tr)) / len(tr)
    return 0.0 if purity + coverage == 0 else 2 * purity * coverage / (purity + coverage)


def score(segments: list[Segment], turns: list[Turn], labels) -> tuple[float, float]:
    line_pairs, split_pairs = [], []
    for seg in segments:
        words = words_of(seg)
        if not words:
            continue
        truth = [truth_at(w[0].ms, labels) for w in words]
        line_spk = assign_line(seg.start_ms, seg.end_ms, turns)
        line_pairs += [(line_spk, t) for t in truth]
        split_pairs += list(zip(split_line(seg, turns), truth))
    return accuracy(line_pairs), accuracy(split_pairs)


# ------------------------------------------------------------- commands


def make_demo(out_dir: Path) -> None:
    """Alternate halves of two LibriSpeech speakers with short pauses."""
    out_dir.mkdir(parents=True, exist_ok=True)
    clips = [read_wav(fetch(url, cache_dir() / "demo" / f"{i}.wav")) for i, url in enumerate(DEMO_CLIPS)]
    halves = []
    for clip in clips:
        mid = len(clip) // 2
        halves.append((clip[:mid], clip[mid:]))
    order = [("A", halves[0][0]), ("B", halves[1][0]), ("A", halves[0][1]), ("B", halves[1][1])]
    pause = np.zeros(int(0.4 * SAMPLE_RATE), dtype=np.float32)  # under the 0.8 s endpoint rule
    parts, labels, t = [], [], 0
    for name, audio in order:
        labels.append((t / SAMPLE_RATE, (t + len(audio)) / SAMPLE_RATE, name))
        parts += [audio, pause]
        t += len(audio) + len(pause)
    write_wav(out_dir / "demo.wav", np.concatenate(parts))
    (out_dir / "demo.labels.txt").write_text("".join(f"{a:.3f}\t{b:.3f}\t{n}\n" for a, b, n in labels))
    print(f"wrote {out_dir / 'demo.wav'} and {out_dir / 'demo.labels.txt'}")


def check_sherpa(m: dict[str, Path], threads: int) -> None:
    """Re-verify the sherpa-onnx behaviour the app relies on (after upgrades).

    1. Token timestamps restart at 0 after each endpoint reset, so the
       app adds the line's start (EndpointSegmenter).
    2. With compute_confidence, two-speaker turns get a confidence in
       (0, 1]; a single cluster yields a negative placeholder (-2 in
       1.13.8), which the app treats as "unknown" (SpeakerSampleCollector).
    """
    a, b = (read_wav(fetch(url, cache_dir() / "demo" / f"{i}.wav")) for i, url in enumerate(DEMO_CLIPS))
    pause = np.zeros(3 * SAMPLE_RATE, dtype=np.float32)
    audio = np.concatenate([a, pause, b])
    segs = transcribe(audio, m, threads)
    second_start = (len(a) + len(pause)) * 1000 // SAMPLE_RATE
    print(f"lines: {len(segs)} (expect 2)")
    if len(segs) >= 2:
        first = segs[1].tokens[0].ms
        ok = abs(first - second_start) < 1500
        print(f"second line's first word at {first} ms, its clip starts at {second_start} ms: "
              f"{'OK, token times restart after reset' if ok else 'MISMATCH: check the offset logic'}")
    d = make_diarizer(m, threads)
    two = [round(t.confidence, 3) for t in diarize(d, audio, -1, DEFAULT_THRESHOLD)]
    one = [round(t.confidence, 3) for t in diarize(d, np.concatenate([a, pause, a]), -1, DEFAULT_THRESHOLD)]
    print(f"confidences, two speakers: {two} (expect values in (0, 1])")
    print(f"confidences, one speaker:  {one} (negative = not computed)")


def evaluate(args) -> None:
    m = models(args.lang, args.embedding)
    audio = read_wav(Path(args.wav))
    labels = read_labels(Path(args.labels))
    true_count = len({n for _, _, n in labels})
    print(f"{args.wav}: {len(audio) / SAMPLE_RATE:.1f} s, {true_count} labelled speakers", file=sys.stderr)
    segments = transcribe(audio, m, args.threads)
    print(f"{len(segments)} transcript lines, {sum(len(words_of(s)) for s in segments)} words", file=sys.stderr)
    sd = make_diarizer(m, args.threads, args.step)
    counts = [-1] + ([true_count] if args.num_speakers is None else [args.num_speakers])
    counts = list(dict.fromkeys(counts))
    print(f"{'threshold':>9} {'speakers':>8} {'found':>5} {'line acc':>8} {'split acc':>9} {'mean conf':>9}")
    for threshold in args.thresholds:
        for n in counts:
            turns = diarize(sd, audio, n, threshold)
            found = len({t.speaker for t in turns})
            line_acc, split_acc = score(segments, turns, labels)
            conf = np.mean([t.confidence for t in turns]) if turns else float("nan")
            print(f"{threshold:9.2f} {'auto' if n == -1 else n:>8} {found:5d} {line_acc:8.1%} {split_acc:9.1%} {conf:9.3f}")


# ------------------------------------------------------------ benchmark

LIBRISPEECH_URL = "https://www.openslr.org/resources/12/dev-clean.tar.gz"
AMI_AUDIO = "https://groups.inf.ed.ac.uk/ami/AMICorpusMirror/amicorpus/{m}/audio/{m}.Array1-01.wav"
AMI_RTTM = "https://raw.githubusercontent.com/pyannote/AMI-diarization-setup/main/only_words/rttms/test/{m}.rttm"
# One meeting from each AMI test-set series; Array1-01 is a single
# distant table microphone, like a tablet lying on the table.
AMI_MEETINGS = ["ES2004a", "IS1009a", "TS3003a", "EN2002a"]
LABEL_SUFFIXES = (".rttm", ".labels.txt", ".csv")


def librispeech() -> Path:
    root = cache_dir() / "LibriSpeech" / "dev-clean"
    if not root.exists():
        import tarfile
        archive = fetch(LIBRISPEECH_URL, cache_dir() / "dev-clean.tar.gz")
        print("unpacking LibriSpeech dev-clean", file=sys.stderr)
        with tarfile.open(archive) as tar:
            tar.extractall(cache_dir(), filter="data")
    return root


def make_conversation(rng: np.random.Generator, speakers: list[Path], minutes: float):
    """Alternate utterances of `speakers` like a conversation.

    A third of the turns are short interjections (0.5-1.5 s cut from the
    start of an utterance); pauses are 0.15-1.2 s, so many speaker changes
    happen inside one transcript line. Each speaker gets its own gain.
    """
    import soundfile as sf
    files = {s: sorted(s.rglob("*.flac")) for s in speakers}
    gain = {s: float(rng.uniform(0.5, 1.0)) for s in speakers}
    parts, labels, t, current = [], [], 0, None
    while t < minutes * 60 * SAMPLE_RATE:
        spk = rng.choice([s for s in speakers if s != current])
        current = spk
        audio, rate = sf.read(files[spk][rng.integers(len(files[spk]))], dtype="float32")
        assert rate == SAMPLE_RATE
        if rng.random() < 1 / 3:
            audio = audio[: int(rng.uniform(0.5, 1.5) * SAMPLE_RATE)]
        elif len(audio) > 8 * SAMPLE_RATE:
            audio = audio[: int(rng.uniform(3, 8) * SAMPLE_RATE)]
        audio = audio * gain[spk]
        labels.append((t / SAMPLE_RATE, (t + len(audio)) / SAMPLE_RATE, spk.name))
        pause = np.zeros(int(rng.uniform(0.15, 1.2) * SAMPLE_RATE), dtype=np.float32)
        parts += [audio, pause]
        t += len(audio) + len(pause)
    return np.concatenate(parts), labels


def make_bench(out_dir: Path, seed: int) -> None:
    """Write the benchmark: synthetic LibriSpeech conversations + AMI meetings."""
    out_dir.mkdir(parents=True, exist_ok=True)
    rng = np.random.default_rng(seed)
    pool = sorted(p for p in librispeech().iterdir() if p.is_dir())
    for n in (2, 3, 4):
        for i in range(4):
            picks = [pool[j] for j in rng.choice(len(pool), n, replace=False)]
            audio, labels = make_conversation(rng, picks, minutes=4)
            stem = out_dir / f"libri-{n}spk-{i}"
            write_wav(stem.with_suffix(".wav"), audio)
            stem.with_suffix(".labels.txt").write_text(
                "".join(f"{a:.3f}\t{b:.3f}\t{name}\n" for a, b, name in labels))
    for m in AMI_MEETINGS:
        fetch(AMI_AUDIO.format(m=m), out_dir / f"ami-{m}.wav")
        fetch(AMI_RTTM.format(m=m), out_dir / f"ami-{m}.rttm")
    print(f"wrote {len(list(out_dir.glob('*.wav')))} recordings to {out_dir}")


def bench_files(bench_dir: Path) -> list[tuple[Path, Path]]:
    pairs = []
    for wav in sorted(bench_dir.glob("*.wav")):
        label = next((wav.with_name(wav.stem + s) for s in LABEL_SUFFIXES
                      if wav.with_name(wav.stem + s).exists()), None)
        if label:
            pairs.append((wav, label))
    return pairs


def file_key(wav: Path, extra: str) -> str:
    import hashlib
    st = wav.resolve().stat()
    return hashlib.sha1(f"{wav.resolve()}|{st.st_size}|{st.st_mtime_ns}|{extra}".encode()).hexdigest()[:16]


def cached_turns(wav: Path, audio: np.ndarray, sd_factory, num_speakers: int, threshold: float,
                 embedding: str = "resnet34", step: float = DEFAULT_STEP_S) -> tuple[list[Turn], float]:
    """Diarizer turns for one setting and the seconds the run took.

    Cached because a long meeting takes minutes. The default model and
    step keep the cache key of earlier runs.
    """
    import json
    import time
    extra = f"{num_speakers}|{threshold:.3f}"
    if (embedding, step) != ("resnet34", DEFAULT_STEP_S):
        extra += f"|{embedding}|{step:g}"
    path = cache_dir() / "turns" / f"{file_key(wav, extra)}.json"
    if path.exists():
        data = json.loads(path.read_text())
        rows, seconds = (data, float("nan")) if isinstance(data, list) else (data["turns"], data["seconds"])
        return [Turn(*row) for row in rows], seconds
    sd = sd_factory()
    started = time.perf_counter()
    turns = diarize(sd, audio, num_speakers, threshold)
    seconds = time.perf_counter() - started
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"turns": [[t.start_ms, t.end_ms, t.speaker, t.confidence] for t in turns],
                                "seconds": seconds}))
    return turns, seconds


def cached_transcript(wav: Path, audio: np.ndarray, m, threads: int, lang: str) -> list[Segment]:
    import json
    path = cache_dir() / "transcripts" / f"{file_key(wav, lang)}.json"
    if path.exists():
        rows = json.loads(path.read_text())
        return [Segment(r["s"], r["e"], r["t"], [Token(a, b) for a, b in r["k"]]) for r in rows]
    segments = transcribe(audio, m, threads)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps([{"s": s.start_ms, "e": s.end_ms, "t": s.text,
                                 "k": [[k.text, k.ms] for k in s.tokens]} for s in segments]))
    return segments


def bench_one(job) -> list[dict]:
    wav, label, thresholds, threads, lang, asr, embedding, step = job
    m = models(lang, embedding)
    audio = read_wav(wav)
    labels = read_labels(label)
    true_count = len({n for _, _, n in labels})
    segments = cached_transcript(wav, audio, m, threads, lang) if asr else []
    sd = None

    def sd_factory():
        nonlocal sd
        sd = sd or make_diarizer(m, threads, step)
        return sd

    rows = []
    for threshold, n in [(t, -1) for t in thresholds] + [(None, true_count)]:
        turns, seconds = cached_turns(wav, audio, sd_factory, n, DEFAULT_THRESHOLD if threshold is None else threshold,
                                      embedding, step)
        _, split_acc = score(segments, turns, labels) if segments else (None, float("nan"))
        rows.append({"file": wav.stem, "group": wav.stem.rsplit("-", 1)[0], "true": true_count,
                     "threshold": threshold, "found": len({t.speaker for t in turns}),
                     "time": time_accuracy(turns, labels), "f1": cluster_f1(turns, labels),
                     "split": split_acc, "rtf": seconds / (len(audio) / SAMPLE_RATE)})
    print(f"  done {wav.stem}", file=sys.stderr)
    return rows


def run_bench(args) -> None:
    from multiprocessing import Pool
    files = bench_files(Path(args.bench))
    if not files:
        sys.exit(f"{args.bench}: no WAV files with labels")
    models(args.lang, args.embedding)  # download once, before the workers start
    jobs = [(w, lab, args.thresholds, args.threads, args.lang, not args.no_asr, args.embedding, args.step)
            for w, lab in files]
    workers = max(1, min(len(jobs), (os.cpu_count() or 4) // args.threads))
    print(f"{len(jobs)} recordings, {workers} at a time, embedding={args.embedding} step={args.step:g} s",
          file=sys.stderr)
    with Pool(workers) as p:
        rows = [r for rs in p.map(bench_one, jobs) for r in rs]

    def table(title: str, sel: list[dict]) -> None:
        print(f"\n{title}")
        print(f"{'threshold':>9} {'F1':>6} {'time acc':>8} {'split acc':>9} {'count ok':>8} {'count err':>9} "
              f"{'RTF':>6}")
        keys = list(dict.fromkeys(r["threshold"] for r in sel))
        for k in keys:
            rs = [r for r in sel if r["threshold"] == k]
            name = f"{k:9.2f}" if k is not None else f"{'fixed n':>9}"
            ok = np.mean([r["found"] == r["true"] for r in rs])
            err = np.mean([r["found"] - r["true"] for r in rs])
            print(f"{name} {np.mean([r['f1'] for r in rs]):6.1%} {np.mean([r['time'] for r in rs]):8.1%} "
                  f"{np.nanmean([r['split'] for r in rs]) if not args.no_asr else float('nan'):9.1%} "
                  f"{ok:8.0%} {err:+9.2f} {np.nanmean([r['rtf'] for r in rs]):6.3f}")

    if args.verbose:
        print(f"\n{'file':<22} {'threshold':>9} {'true':>4} {'found':>5} {'F1':>6} {'time acc':>8} {'split acc':>9}")
        for r in rows:
            th = f"{r['threshold']:.2f}" if r["threshold"] is not None else "fixed n"
            print(f"{r['file']:<22} {th:>9} {r['true']:4d} {r['found']:5d} {r['f1']:6.1%} {r['time']:8.1%} "
                  f"{r['split']:9.1%}")
    for g in sorted({r["group"] for r in rows}):
        table(f"{g} ({len({r['file'] for r in rows if r['group'] == g})} recordings)",
              [r for r in rows if r["group"] == g])
    table(f"all ({len(files)} recordings)", rows)
    print("\n'fixed n' = speaker count set to the labelled count (the best Re-identify can do).")
    print("F1 = cluster purity/coverage F1, the figure to tune the threshold on;")
    print("count err = mean (found - labelled) speakers; positive means too many.")
    print(f"RTF = diarization seconds per second of audio with --threads {args.threads}, while other")
    print("recordings run alongside (nan when the turns came from the cache of an older run).")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("wav", nargs="?", help="16-bit mono WAV recording")
    p.add_argument("labels", nargs="?", help="Audacity label file or CSV (start,end,speaker in seconds)")
    p.add_argument("--lang", choices=sorted(ASR_BASE), default="en")
    p.add_argument("--threads", type=int, default=4)
    p.add_argument("--thresholds", type=float, nargs="+", default=[0.5, 0.6, 0.65, 0.7, 0.75, 0.8, 0.85, 0.9])
    p.add_argument("--num-speakers", type=int, help="fixed count to compare with auto (default: labelled count)")
    p.add_argument("--embedding", choices=list(EMBEDDINGS), default="resnet34",
                   help="speaker identification voice model (Settings -> Speakers -> Voice model)")
    p.add_argument("--step", type=float, default=DEFAULT_STEP_S,
                   help="segmentation step in seconds over its 10 s window (Settings -> Speakers -> Analysis step)")
    p.add_argument("--make-demo", metavar="DIR", help="write a two-speaker demo WAV + labels to DIR and exit")
    p.add_argument("--check-sherpa", action="store_true", help="verify sherpa-onnx behaviour the app relies on")
    p.add_argument("--make-bench", metavar="DIR",
                   help="write the benchmark (synthetic LibriSpeech conversations + AMI meetings) to DIR and exit")
    p.add_argument("--seed", type=int, default=1, help="random seed for --make-bench")
    p.add_argument("--bench", metavar="DIR", help="score every WAV in DIR that has a label file next to it")
    p.add_argument("--no-asr", action="store_true", help="--bench: skip transcription, report time accuracy only")
    p.add_argument("-v", "--verbose", action="store_true", help="--bench: also print one row per recording")
    args = p.parse_args()
    if args.make_bench:
        make_bench(Path(args.make_bench), args.seed)
    elif args.bench:
        run_bench(args)
    elif args.make_demo:
        make_demo(Path(args.make_demo))
    elif args.check_sherpa:
        check_sherpa(models(args.lang), args.threads)
    elif args.wav and args.labels:
        evaluate(args)
    else:
        p.error("give WAV and LABELS, or --make-demo DIR, or --check-sherpa")


if __name__ == "__main__":
    main()
