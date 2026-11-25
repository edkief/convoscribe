'use client';

import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { formatBytes } from '@/lib/format';
import { IconAlert, IconCheckCircle, IconDownload, IconMic, IconSpinner, IconTrash } from '../Icons';
import { BUNDLES, bundleBytes, bundleFor, type AsrBundle } from './asr-catalog';
import { bundleReady, deleteBundle, downloadBundle } from './model-store';
import { recordingSupported } from './opfs';
import { appendDraftLine, markDraftDropped, putDraft } from './queue-store';
import { Recorder as AudioRecorder, type TranscriptionState } from './recorder';
import { recordingLock } from './recovery';
import type { FinalLine } from './segmenter';
import { discardRecording, enqueue, holdLock } from './uploads';

type ModelState =
  | { kind: 'checking' }
  | { kind: 'missing' }
  | { kind: 'downloading'; bytes: number }
  | { kind: 'ready' }
  | { kind: 'error'; message: string };

type Phase =
  | { kind: 'idle' }
  | { kind: 'starting' }
  | { kind: 'recording'; since: number }
  | { kind: 'saving' }
  | { kind: 'saved'; transcript: boolean }
  | { kind: 'error'; message: string };

const LANGUAGE_KEY = 'convoscribe.record.language';
const TRANSCRIBE_KEY = 'convoscribe.record.transcribe';

const clock = (ms: number) => {
  const s = Math.floor(ms / 1000);
  const hms = [Math.floor(s / 3600), Math.floor(s / 60) % 60, s % 60];
  return (hms[0] ? [hms[0], ...hms.slice(1).map((n) => String(n).padStart(2, '0'))] : [hms[1], String(hms[2]).padStart(2, '0')]).join(':');
};

function startFailure(err: unknown): string {
  const name = err instanceof DOMException ? err.name : '';
  if (name === 'NotAllowedError') return 'Microphone access was refused. Allow it for this site in the browser’s settings, then try again.';
  if (name === 'NotFoundError') return 'No microphone was found.';
  return `Recording could not start: ${err instanceof Error ? err.message : String(err)}`;
}

/**
 * The Record page (#164): record from the microphone with, if its model is
 * downloaded, a live transcript made on this computer. Nothing here needs
 * the network while recording; on Stop the recording joins the upload
 * queue in the app frame and becomes a session once it is uploaded. Links
 * and forms are held while recording, and leaving the page warns.
 */
export function Recorder() {
  const [supported, setSupported] = useState<boolean | null>(null);
  const [language, setLanguage] = useState<AsrBundle['language']>('en');
  const [transcribe, setTranscribe] = useState(true);
  const [model, setModel] = useState<ModelState>({ kind: 'checking' });
  const [phase, setPhase] = useState<Phase>({ kind: 'idle' });
  const [now, setNow] = useState(0);
  const [level, setLevel] = useState(0);
  const [lines, setLines] = useState<FinalLine[]>([]);
  const [partial, setPartial] = useState('');
  const [transcription, setTranscription] = useState<TranscriptionState>({ kind: 'off' });
  const [held, setHeld] = useState(false);
  const recorder = useRef<AudioRecorder | null>(null);
  /** What the recording needs at Stop, set at Start; `release` frees its Web Lock (#165). */
  const pending = useRef<{
    syncId: string;
    createdAt: number;
    bundle: AsrBundle | null;
    language: string;
    zone: string | null;
    release: () => void;
  } | null>(null);
  const download = useRef<AbortController | null>(null);
  const transcriptEnd = useRef<HTMLDivElement | null>(null);

  const bundle = bundleFor(language)!;
  const busy = phase.kind === 'starting' || phase.kind === 'recording' || phase.kind === 'saving';

  // Settings from the last visit; what this browser can do.
  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- read once from browser-only APIs after hydration */
    setSupported(recordingSupported());
    const saved = localStorage.getItem(LANGUAGE_KEY);
    if (saved && bundleFor(saved)) setLanguage(saved as AsrBundle['language']);
    else if (navigator.language.toLowerCase().startsWith('fr')) setLanguage('fr');
    setTranscribe(localStorage.getItem(TRANSCRIBE_KEY) !== 'off');
    /* eslint-enable react-hooks/set-state-in-effect */
  }, []);

  const checkModel = useCallback(async (b: AsrBundle) => {
    setModel({ kind: 'checking' });
    setModel((await bundleReady(b).catch(() => false)) ? { kind: 'ready' } : { kind: 'missing' });
  }, []);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- the check resolves asynchronously
    checkModel(bundle);
  }, [bundle, checkModel]);

  // The clock while recording.
  useEffect(() => {
    if (phase.kind !== 'recording') return;
    const tick = () => setNow(Date.now() - phase.since);
    tick();
    const timer = setInterval(tick, 500);
    return () => clearInterval(timer);
  }, [phase]);

  useEffect(() => {
    transcriptEnd.current?.scrollIntoView({ block: 'nearest' });
  }, [lines, partial]);

  const finish = useCallback(async (onPage: boolean) => {
    const rec = recorder.current;
    const meta = pending.current;
    if (!rec || !meta) return;
    recorder.current = null;
    pending.current = null;
    if (onPage) setPhase({ kind: 'saving' });
    try {
      const done = await rec.stop();
      await enqueue({
        syncId: meta.syncId,
        audioSha: done.sha256,
        size: done.size,
        durationMs: done.durationMs,
        createdAt: meta.createdAt,
        language: meta.language,
        zone: meta.zone,
        asrModel: done.lines ? meta.bundle?.id ?? null : null,
        lines: done.lines ?? [],
        uploadId: null,
        stored: false,
        failed: null,
        queuedAt: Date.now(),
      });
      if (onPage) setPhase({ kind: 'saved', transcript: done.lines !== null });
    } catch (err) {
      rec.abort();
      await discardRecording(meta.syncId);
      if (onPage) setPhase({ kind: 'error', message: `The recording could not be saved: ${err instanceof Error ? err.message : String(err)}` });
    } finally {
      meta.release();
    }
  }, []);

  // Leaving the page in the app (the back button) stops the recording and keeps it.
  useEffect(() => () => void finish(false), [finish]);

  // While recording: warn before the page unloads, and hold links and forms, which would end it.
  useEffect(() => {
    if (!busy) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    const hold = (e: Event) => {
      const target = e.target as Element | null;
      const link = e.type === 'click' ? target?.closest?.('a[href]') : target;
      if (!link) return;
      if (link instanceof HTMLAnchorElement && (link.target === '_blank' || link.origin !== location.origin)) return;
      e.preventDefault();
      e.stopPropagation();
      setHeld(true);
    };
    window.addEventListener('beforeunload', warn);
    document.addEventListener('click', hold, true);
    document.addEventListener('submit', hold, true);
    return () => {
      window.removeEventListener('beforeunload', warn);
      document.removeEventListener('click', hold, true);
      document.removeEventListener('submit', hold, true);
    };
  }, [busy]);

  const start = async () => {
    const useModel = transcribe && model.kind === 'ready' ? bundle : null;
    const syncId = crypto.randomUUID();
    setPhase({ kind: 'starting' });
    setLines([]);
    setPartial('');
    setHeld(false);
    setLevel(0);
    setNow(0);
    // Held while recording, so no tab offers to recover this recording (#165).
    const release = (await holdLock(recordingLock(syncId))) ?? (() => {});
    const meta = {
      syncId,
      createdAt: Date.now(),
      bundle: useModel,
      language,
      zone: Intl.DateTimeFormat().resolvedOptions().timeZone ?? null,
      release,
    };
    const keep = (what: Promise<void>) => what.catch((err) => console.warn(`recording draft: ${err}`));
    // The draft comes before the file, so a file without one is never a recording in progress.
    await keep(
      putDraft({ syncId, createdAt: meta.createdAt, language, zone: meta.zone, asrModel: useModel?.id ?? null, transcriptDropped: false }),
    );
    navigator.storage?.persist?.().catch(() => {});
    try {
      recorder.current = await AudioRecorder.start(meta.syncId, useModel, {
        level: setLevel,
        partial: setPartial,
        line: (line) => {
          setLines((all) => [...all, line]);
          keep(appendDraftLine(syncId, line));
        },
        transcription: (state) => {
          setTranscription(state);
          if (state.kind === 'dropped') keep(markDraftDropped(syncId));
        },
        error: (message) => {
          recorder.current?.abort();
          recorder.current = null;
          pending.current = null;
          discardRecording(meta.syncId).finally(release);
          setPhase({ kind: 'error', message: `Recording stopped: ${message}` });
        },
      });
      pending.current = meta;
      setPhase({ kind: 'recording', since: Date.now() });
    } catch (err) {
      await discardRecording(meta.syncId);
      release();
      setPhase({ kind: 'error', message: startFailure(err) });
    }
  };

  const startDownload = async () => {
    const controller = new AbortController();
    download.current = controller;
    let bytes = 0;
    setModel({ kind: 'downloading', bytes });
    try {
      await downloadBundle(
        bundle,
        (n) => {
          bytes += n;
          setModel({ kind: 'downloading', bytes });
        },
        controller.signal,
      );
      setModel({ kind: 'ready' });
    } catch (err) {
      setModel(
        controller.signal.aborted
          ? { kind: 'missing' }
          : { kind: 'error', message: err instanceof Error ? err.message : String(err) },
      );
    } finally {
      download.current = null;
    }
  };

  const remove = async () => {
    await deleteBundle(
      bundle,
      BUNDLES.filter((b) => b !== bundle),
    );
    await checkModel(bundle);
  };

  if (supported === false) {
    return (
      <div className="notice warn">
        <IconAlert size={16} />
        This browser can’t record here: it needs private file storage (OPFS) and audio worklets. Use a recent Chrome or Safari.
      </div>
    );
  }

  const size = bundleBytes(bundle);
  const recording = phase.kind === 'recording';

  return (
    <div className="record">
      <section className="card">
        <div className="card-body record-settings">
          <label className="field">
            <span>Language</span>
            <select
              value={language}
              disabled={busy}
              onChange={(e) => {
                const value = e.target.value as AsrBundle['language'];
                setLanguage(value);
                localStorage.setItem(LANGUAGE_KEY, value);
              }}
            >
              {BUNDLES.map((b) => (
                <option key={b.language} value={b.language}>
                  {b.label}
                </option>
              ))}
            </select>
          </label>
          <label className="record-toggle">
            <input
              type="checkbox"
              checked={transcribe}
              disabled={busy}
              onChange={(e) => {
                setTranscribe(e.target.checked);
                localStorage.setItem(TRANSCRIBE_KEY, e.target.checked ? 'on' : 'off');
              }}
            />
            <span>Live transcript</span>
          </label>
          {transcribe && (
            <div className="record-model">
              {model.kind === 'checking' && <span className="muted">Checking the speech model…</span>}
              {model.kind === 'missing' && (
                <>
                  <span className="muted">
                    The {bundle.label} speech model isn’t on this computer yet ({formatBytes(size)}, downloaded once).
                  </span>
                  <button type="button" className="button" disabled={busy} onClick={startDownload}>
                    <IconDownload size={16} />
                    Download
                  </button>
                </>
              )}
              {model.kind === 'downloading' && (
                <>
                  <progress max={size} value={Math.min(model.bytes, size)} aria-label="Speech model download" />
                  <span className="muted">{Math.floor((100 * Math.min(model.bytes, size)) / size)}%</span>
                  <button type="button" className="link-button" onClick={() => download.current?.abort()}>
                    Cancel
                  </button>
                </>
              )}
              {model.kind === 'ready' && (
                <>
                  <span className="muted">
                    <IconCheckCircle size={16} /> {bundle.label} speech model on this computer
                  </span>
                  <button type="button" className="icon-button" title="Delete the model" aria-label="Delete the model" disabled={busy} onClick={remove}>
                    <IconTrash size={16} />
                  </button>
                </>
              )}
              {model.kind === 'error' && (
                <>
                  <span className="danger-text">Download failed: {model.message}</span>
                  <button type="button" className="button" disabled={busy} onClick={startDownload}>
                    Try again
                  </button>
                </>
              )}
            </div>
          )}
        </div>
      </section>

      <section className="card record-main">
        <div className="card-body">
          <div className="record-controls">
            {recording ? (
              <button type="button" className="record-button stop" onClick={() => finish(true)} aria-label="Stop recording">
                <span className="record-square" />
              </button>
            ) : (
              <button
                type="button"
                className="record-button"
                onClick={start}
                disabled={busy || model.kind === 'downloading'}
                aria-label="Start recording"
              >
                {phase.kind === 'starting' || phase.kind === 'saving' ? <IconSpinner size={28} className="spin" /> : <IconMic size={28} />}
              </button>
            )}
            <div className="record-status">
              <strong className="record-clock">{recording ? clock(now) : phase.kind === 'saving' ? 'Saving…' : 'Ready'}</strong>
              <meter className="record-level" min={0} max={1} value={recording ? Math.min(1, level * 4) : 0} aria-label="Microphone level" />
              <TranscriptionLine state={recording || phase.kind === 'saving' ? transcription : { kind: 'off' }} wanted={transcribe} modelReady={model.kind === 'ready'} />
            </div>
          </div>

          {held && recording && (
            <p className="notice">Stop the recording before leaving this page.</p>
          )}
          {phase.kind === 'saved' && (
            <p className="notice">
              <IconCheckCircle size={16} />
              <span>
                Saved{phase.transcript ? '' : ' without a transcript'}. It uploads in the background and appears in the{' '}
                <Link href="/">library</Link> once it’s on the server.
              </span>
            </p>
          )}
          {phase.kind === 'error' && (
            <p className="notice warn">
              <IconAlert size={16} />
              <span>{phase.message}</span>
            </p>
          )}

          {(lines.length > 0 || partial) && (
            <div className="live-transcript" aria-live="polite">
              {lines.map((line) => (
                <p key={line.startMs}>
                  <span className="live-time">{clock(line.startMs)}</span>
                  {line.text}
                </p>
              ))}
              {partial && (
                <p className="live-partial">
                  <span className="live-time">…</span>
                  {partial}
                </p>
              )}
              <div ref={transcriptEnd} />
            </div>
          )}
        </div>
      </section>
    </div>
  );
}

function TranscriptionLine({ state, wanted, modelReady }: { state: TranscriptionState; wanted: boolean; modelReady: boolean }) {
  switch (state.kind) {
    case 'loading':
      return <span className="muted">Loading the speech model…</span>;
    case 'live':
      return (
        <span className="muted" title="Time spent transcribing for each second of audio; under 1× keeps up.">
          Live transcript{state.speed !== null && ` · speed ${state.speed.toFixed(2)}× real time`}
        </span>
      );
    case 'dropped':
      return <span className="danger-text">{state.reason} The recording goes on and is saved without a transcript.</span>;
    case 'off':
      if (!wanted) return <span className="muted">No live transcript</span>;
      return <span className="muted">{modelReady ? 'Live transcript on' : 'No live transcript until the speech model is downloaded'}</span>;
  }
}
