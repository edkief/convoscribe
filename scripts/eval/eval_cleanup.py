#!/usr/bin/env python3
"""Measure what noise cleanup (issue #37) does to ConvoScribe's transcripts.

Runs the app's streaming Kroko ASR (SherpaAsrConfigs.cpu: endpoint rules,
greedy search, 100 ms chunks) on short read-speech clips with known
transcripts, clean and mixed with noise at several signal-to-noise
ratios, and scores the word error rate (WER):

  * without cleanup;
  * for each streaming denoiser (sherpa-onnx OnlineSpeechDenoiser), fed
    in 100 ms chunks as RecordingController does live (the file cleanup
    path uses the same streaming cleaner):
      - "mix":  what the app's recognizer hears, the cleaned audio
                blended with the original (AudioCleaning.RECOGNIZER_CLEAN_SHARE);
      - "full": the fully cleaned audio, what the player plays.

Noise is synthetic so the run needs nothing but the clips:
  * white:  Gaussian white noise;
  * pink:   1/f noise, closer to traffic and air conditioning;
  * babble: three other speakers from the same set, summed and looped,
            like a busy cafe or open office.

Data (downloaded on first use):
  * en: LibriSpeech dev-clean sample (hf-internal-testing/librispeech_asr_dummy, 73 clips);
  * fr: Multilingual LibriSpeech French, 1-hour subset (facebook/multilingual_librispeech).

Also prints each denoiser's real-time factor on this machine, which is
far faster than a phone: use the app's Engine check for the device verdict.

Keep the settings in sync with:
  app/src/main/java/com/convoscribe/engine/sherpa/SherpaAsrConfigs.kt
  app/src/main/java/com/convoscribe/engine/sherpa/SherpaAudioCleaner.kt
"""

from __future__ import annotations

import argparse
import io
import os
import re
import sys
import time
import unicodedata
import urllib.request
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import sherpa_onnx
import soundfile as sf

SAMPLE_RATE = 16_000
CHUNK = 1_600
# AudioCleaning.RECOGNIZER_CLEAN_SHARE
CLEAN_SHARE = 0.5
# Streaming DPDFNet returns its output 40 ms late; GTCRN is index-aligned.
LAG = {"gtcrn": 0, "dpdfnet": 640}

HF = "https://huggingface.co"
ASR = {
    "en": f"{HF}/csukuangfj/sherpa-onnx-streaming-zipformer-en-kroko-2025-08-06/resolve/main/",
    "fr": f"{HF}/csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06/resolve/main/",
}
DATA = {
    "en": f"{HF}/datasets/hf-internal-testing/librispeech_asr_dummy/resolve/main/clean/"
    "validation-00000-of-00001.parquet",
    "fr": f"{HF}/datasets/facebook/multilingual_librispeech/resolve/main/french/1_hours-00000-of-00001.parquet",
}
ENHANCEMENT = "https://github.com/k2-fsa/sherpa-onnx/releases/download/speech-enhancement-models/"
DENOISERS = {"gtcrn": "gtcrn_simple.onnx", "dpdfnet": "dpdfnet_baseline.onnx"}

CACHE = Path(os.environ.get("CONVOSCRIBE_EVAL_CACHE", Path.home() / ".cache" / "convoscribe-eval"))


def fetch(url: str, target: Path) -> Path:
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        print(f"downloading {url}", file=sys.stderr)
        part = target.with_suffix(target.suffix + ".part")
        urllib.request.urlretrieve(url, part)
        part.rename(target)
    return target


def asr_dir(lang: str) -> Path:
    folder = CACHE / f"asr-{lang}"
    for name in ("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt"):
        fetch(ASR[lang] + name, folder / name)
    return folder


def denoiser_path(name: str) -> Path:
    return fetch(ENHANCEMENT + DENOISERS[name], CACHE / "enhancement" / DENOISERS[name])


def load_clips(lang: str, limit: int) -> list[tuple[np.ndarray, str]]:
    table = pq.read_table(fetch(DATA[lang], CACHE / "data" / f"{lang}.parquet")).to_pylist()
    clips = []
    for row in table[:limit]:
        audio, rate = sf.read(io.BytesIO(row["audio"]["bytes"]), dtype="float32")
        if audio.ndim > 1:
            audio = audio.mean(axis=1)
        if rate != SAMPLE_RATE:
            raise SystemExit(f"{lang}: expected 16 kHz clips, got {rate}")
        clips.append((audio, row.get("text") or row.get("transcript") or ""))
    return clips


def recognizer(lang: str) -> sherpa_onnx.OnlineRecognizer:
    folder = asr_dir(lang)
    # SherpaAsrConfigs.endpoint and .cpu
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        encoder=str(folder / "encoder.onnx"),
        decoder=str(folder / "decoder.onnx"),
        joiner=str(folder / "joiner.onnx"),
        tokens=str(folder / "tokens.txt"),
        num_threads=1,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
        enable_endpoint_detection=True,
        rule1_min_trailing_silence=2.4,
        rule2_min_trailing_silence=0.8,
        rule3_min_utterance_length=20.0,
        model_type="zipformer2",
    )


def denoiser(name: str) -> sherpa_onnx.OnlineSpeechDenoiser:
    model = denoiser_path(name)
    # SherpaAudioCleaner: one thread, CPU
    if name == "gtcrn":
        config = sherpa_onnx.OfflineSpeechDenoiserModelConfig(
            gtcrn=sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=str(model)),
            num_threads=1,
            provider="cpu",
        )
    else:
        config = sherpa_onnx.OfflineSpeechDenoiserModelConfig(
            dpdfnet=sherpa_onnx.OfflineSpeechDenoiserDpdfNetModelConfig(model=str(model)),
            num_threads=1,
            provider="cpu",
        )
    return sherpa_onnx.OnlineSpeechDenoiser(sherpa_onnx.OnlineSpeechDenoiserConfig(model=config))


def clean(audio: np.ndarray, den: sherpa_onnx.OnlineSpeechDenoiser, lag: int = 0) -> np.ndarray:
    """100 ms chunks in, aligned cleaned audio out (AlignedCleaner), [lag] shifted out."""
    out = []
    for start in range(0, len(audio), CHUNK):
        out.append(np.asarray(den.run(audio[start : start + CHUNK], SAMPLE_RATE).samples, dtype=np.float32))
    out.append(np.asarray(den.flush().samples, dtype=np.float32))
    cleaned = np.concatenate(out)[lag : lag + len(audio)]
    return np.pad(cleaned, (0, len(audio) - len(cleaned)))


def transcribe(rec: sherpa_onnx.OnlineRecognizer, audio: np.ndarray) -> str:
    """EndpointSegmenter: feed 100 ms chunks, keep each closed utterance."""
    stream = rec.create_stream()
    lines = []
    for start in range(0, len(audio), CHUNK):
        stream.accept_waveform(SAMPLE_RATE, audio[start : start + CHUNK])
        while rec.is_ready(stream):
            rec.decode_stream(stream)
        if rec.is_endpoint(stream):
            lines.append(rec.get_result(stream))
            rec.reset(stream)
    stream.accept_waveform(SAMPLE_RATE, np.zeros(int(0.66 * SAMPLE_RATE), dtype=np.float32))
    stream.input_finished()
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    lines.append(rec.get_result(stream))
    return " ".join(line for line in lines if line.strip())


def words(text: str) -> list[str]:
    text = unicodedata.normalize("NFC", text.lower()).replace("’", "'")
    return re.sub(r"[^\w' ]+", " ", text).split()


def edits(ref: list[str], hyp: list[str]) -> int:
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return row[-1]


def noise(kind: str, length: int, rng: np.random.Generator, others: list[np.ndarray]) -> np.ndarray:
    if kind == "white":
        return rng.standard_normal(length).astype(np.float32)
    if kind == "pink":
        spectrum = np.fft.rfft(rng.standard_normal(length))
        freqs = np.arange(len(spectrum))
        freqs[0] = 1
        return np.fft.irfft(spectrum / np.sqrt(freqs), n=length).astype(np.float32)
    picks = rng.choice(len(others), size=min(3, len(others)), replace=False)
    out = np.zeros(length, dtype=np.float32)
    for k in picks:
        voice = others[k]
        offset = rng.integers(0, len(voice))
        out += np.resize(np.roll(voice, -offset), length)
    return out


def mix(speech: np.ndarray, noise_: np.ndarray, snr_db: float) -> np.ndarray:
    p_speech = np.mean(speech**2)
    p_noise = np.mean(noise_**2) or 1.0
    mixed = speech + noise_ * np.sqrt(p_speech / (p_noise * 10 ** (snr_db / 10)))
    peak = np.max(np.abs(mixed))
    return mixed / peak * 0.9 if peak > 1.0 else mixed


def conditions(snrs: list[float]) -> list[tuple[str, float | None]]:
    return [("clean", None)] + [(kind, snr) for kind in ("white", "pink", "babble") for snr in snrs]


def run_condition(args: tuple[str, int, str, float | None, list[str]]) -> list[tuple[str, int, int, float, float]]:
    """Score one language and noise condition for every cleanup; one process each."""
    lang, limit, kind, snr, cleanups = args
    clips = load_clips(lang, limit)
    rng = np.random.default_rng(37)
    rec = recognizer(lang)
    others = [audio for audio, _ in clips]
    noisy = []
    for i, (audio, ref) in enumerate(clips):
        if kind == "clean":
            noisy.append((audio, ref))
        else:
            pool = others[:i] + others[i + 1 :]
            noisy.append((mix(audio, noise(kind, len(audio), rng, pool), snr), ref))
    results = []
    for name in cleanups:
        den = None if name == "none" else denoiser(name)
        errors = {"none": 0} if den is None else {"mix": 0, "full": 0}
        total = 0
        clean_s = audio_s = 0.0
        for audio, ref in noisy:
            ref_words = words(ref)
            total += len(ref_words)
            audio_s += len(audio) / SAMPLE_RATE
            if den is None:
                errors["none"] += edits(ref_words, words(transcribe(rec, audio)))
                continue
            start = time.perf_counter()
            cleaned = clean(audio, den, LAG[name])
            clean_s += time.perf_counter() - start
            mixed = CLEAN_SHARE * cleaned + (1 - CLEAN_SHARE) * audio
            errors["mix"] += edits(ref_words, words(transcribe(rec, mixed)))
            errors["full"] += edits(ref_words, words(transcribe(rec, cleaned)))
        for variant, count in errors.items():
            label = name if variant == "none" else f"{name} {variant}"
            results.append((label, name, count, total, clean_s, audio_s))
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--langs", nargs="+", default=["en", "fr"], choices=["en", "fr"])
    parser.add_argument("--limit", type=int, default=73, help="clips per language (default: 73)")
    parser.add_argument("--snr", nargs="+", type=float, default=[0.0, 5.0, 10.0], help="SNRs in dB")
    parser.add_argument("--cleanup", nargs="+", default=["none", "gtcrn", "dpdfnet"])
    parser.add_argument("--jobs", type=int, default=os.cpu_count() or 1)
    args = parser.parse_args()

    for lang in args.langs:  # download once, before the workers start
        asr_dir(lang)
        load_clips(lang, 1)
    for name in args.cleanup:
        if name != "none":
            denoiser_path(name)

    tasks = [(lang, args.limit, kind, snr, args.cleanup) for lang in args.langs for kind, snr in conditions(args.snr)]
    with ProcessPoolExecutor(max_workers=args.jobs) as pool:
        outcomes = list(pool.map(run_condition, tasks))

    speed: dict[str, list[float]] = {}
    labels = [label for label, *_ in outcomes[0]]
    print("| lang | noise | SNR | " + " | ".join(labels) + " |")
    print("|" + "---|" * (3 + len(labels)))
    for (lang, _, kind, snr, _), results in zip(tasks, outcomes):
        cells = []
        for label, name, errors, total, clean_s, audio_s in results:
            cells.append(f"{100 * errors / max(total, 1):.1f}%")
            if label.endswith(" mix"):
                speed.setdefault(name, [0.0, 0.0])
                speed[name][0] += clean_s
                speed[name][1] += audio_s
        snr_cell = "–" if snr is None else f"{snr:g} dB"
        print(f"| {lang} | {kind} | {snr_cell} | " + " | ".join(cells) + " |")
    print()
    for name, (clean_s, audio_s) in speed.items():
        print(f"{name}: real-time factor {clean_s / audio_s:.3f} on this machine (one thread)")
    print("\nCells are word error rates; 'mix' is what the app's recognizer hears.")


if __name__ == "__main__":
    main()
