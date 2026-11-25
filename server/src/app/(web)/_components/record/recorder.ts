import { SAMPLE_RATE, type AsrBundle } from './asr-catalog';
import { CAPTURE_PROCESSOR, CAPTURE_WORKLET } from './capture-worklet';
import type { RecognizerIn, RecognizerOut } from './recognizer.worker';
import type { FinalLine } from './segmenter';
import type { WriterIn, WriterOut } from './writer.worker';

/** Audio the recognizer may fall behind by before it is stopped and the recording kept without a transcript. */
export const MAX_BACKLOG_SAMPLES = 20 * SAMPLE_RATE;
/** How long Stop waits for the recognizer to finish the last words. */
const FINISH_TIMEOUT_MS = 30_000;

/** Where the live transcript stands. */
export type TranscriptionState =
  | { kind: 'off' }
  | { kind: 'loading' }
  /** `speed`: decoding time over audio time, under 1 when it keeps up. */
  | { kind: 'live'; speed: number | null }
  /** Stopped during the recording; the recording goes on, without a transcript. */
  | { kind: 'dropped'; reason: string };

export type RecorderEvents = {
  level(rms: number): void;
  partial(text: string): void;
  line(line: FinalLine): void;
  transcription(state: TranscriptionState): void;
  /** Recording itself failed (storage, microphone): the page should stop. */
  error(message: string): void;
};

export type Recording = { sha256: string; size: number; durationMs: number; lines: FinalLine[] | null };

const AUDIO: MediaTrackConstraints = {
  channelCount: 1,
  // The app records the raw microphone (VOICE_RECOGNITION): no processing.
  echoCancellation: false,
  noiseSuppression: false,
  autoGainControl: false,
};

function once<T extends { type: string }>(worker: Worker, types: T['type'][], timeoutMs?: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const timer = timeoutMs ? setTimeout(() => reject(new Error('timeout')), timeoutMs) : undefined;
    const listener = (e: MessageEvent<T>) => {
      if (!types.includes(e.data.type)) return;
      clearTimeout(timer);
      worker.removeEventListener('message', listener);
      resolve(e.data);
    };
    worker.addEventListener('message', listener);
  });
}

/**
 * One recording on the Record page (#164): the microphone through an
 * AudioWorklet at 16 kHz, written to OPFS by one worker and transcribed by
 * another. Nothing here touches the network. The recognizer is optional and
 * expendable: if it fails to load, crashes or falls more than 20 s behind,
 * it is stopped and the recording carries on without a transcript.
 */
export class Recorder {
  private lines: FinalLine[] = [];
  private sent = 0;
  private processed = 0;
  private computeMs = 0;
  private transcription: TranscriptionState;
  private context: AudioContext | null = null;
  private node: AudioWorkletNode | null = null;
  private stream: MediaStream | null = null;
  private wakeLock: { release(): Promise<void> } | null = null;

  private constructor(
    private readonly writer: Worker,
    private recognizer: Worker | null,
    private readonly events: RecorderEvents,
  ) {
    this.transcription = recognizer ? { kind: 'loading' } : { kind: 'off' };
    events.transcription(this.transcription);
    writer.addEventListener('message', (e: MessageEvent<WriterOut>) => {
      if (e.data.type === 'failed') events.error(e.data.message);
    });
    if (recognizer) {
      recognizer.addEventListener('message', (e: MessageEvent<RecognizerOut>) => this.onRecognizer(e.data));
      recognizer.addEventListener('error', () => this.drop('The speech recognizer stopped.'));
    }
  }

  /** Open the file, then the microphone; `bundle` null records without a transcript. */
  static async start(syncId: string, bundle: AsrBundle | null, events: RecorderEvents): Promise<Recorder> {
    const writer = new Worker(new URL('./writer.worker.ts', import.meta.url), { type: 'module', name: 'recording-writer' });
    const opened = once<WriterOut>(writer, ['started', 'failed']);
    writer.postMessage({ type: 'start', syncId } satisfies WriterIn);
    const answer = await opened;
    if (answer.type === 'failed') {
      writer.terminate();
      throw new Error(answer.message);
    }
    let recognizer: Worker | null = null;
    if (bundle) {
      recognizer = new Worker(new URL('./recognizer.worker.ts', import.meta.url), { type: 'module', name: 'speech-recognizer' });
      recognizer.postMessage({ type: 'load', bundleId: bundle.id } satisfies RecognizerIn);
    }
    const recorder = new Recorder(writer, recognizer, events);
    try {
      await recorder.openMicrophone();
    } catch (err) {
      recorder.abort();
      throw err;
    }
    return recorder;
  }

  private async openMicrophone() {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: AUDIO });
    let context = new AudioContext({ sampleRate: SAMPLE_RATE, latencyHint: 'playback' });
    let source: MediaStreamAudioSourceNode;
    try {
      source = context.createMediaStreamSource(this.stream);
    } catch {
      // Firefox will not connect a microphone at another rate: let the worklet downsample.
      await context.close();
      context = new AudioContext({ latencyHint: 'playback' });
      source = context.createMediaStreamSource(this.stream);
    }
    this.context = context;
    const moduleUrl = URL.createObjectURL(new Blob([CAPTURE_WORKLET], { type: 'text/javascript' }));
    try {
      await context.audioWorklet.addModule(moduleUrl);
    } finally {
      URL.revokeObjectURL(moduleUrl);
    }
    const node = new AudioWorkletNode(context, CAPTURE_PROCESSOR, {
      numberOfInputs: 1,
      numberOfOutputs: 1,
      channelCount: 1,
      processorOptions: { targetRate: SAMPLE_RATE },
    });
    node.port.onmessage = (e: MessageEvent<{ samples?: Float32Array; rms?: number }>) => {
      const { samples, rms } = e.data;
      if (!samples) return;
      this.events.level(rms ?? 0);
      if (this.recognizer) {
        const copy = samples.slice();
        this.sent += copy.length;
        this.recognizer.postMessage({ type: 'audio', samples: copy } satisfies RecognizerIn, [copy.buffer]);
        if (this.sent - this.processed > MAX_BACKLOG_SAMPLES) this.drop('Transcription could not keep up with the recording.');
      }
      this.writer.postMessage({ type: 'audio', samples } satisfies WriterIn, [samples.buffer]);
    };
    // A silent output keeps the node pulled by the graph in every browser.
    const mute = context.createGain();
    mute.gain.value = 0;
    source.connect(node).connect(mute).connect(context.destination);
    this.node = node;
    if (context.state === 'suspended') await context.resume();
    this.wakeLock = await (navigator as Navigator & { wakeLock?: { request(type: 'screen'): Promise<{ release(): Promise<void> }> } }).wakeLock
      ?.request('screen')
      .catch(() => null) ?? null;
  }

  private onRecognizer(message: RecognizerOut) {
    switch (message.type) {
      case 'ready':
        this.setTranscription({ kind: 'live', speed: null });
        break;
      case 'events':
        this.processed = message.processed;
        this.computeMs += message.computeMs;
        this.apply(message.events);
        if (this.transcription.kind === 'live') {
          this.setTranscription({ kind: 'live', speed: this.computeMs / ((this.processed / SAMPLE_RATE) * 1000) });
        }
        break;
      case 'finished':
        this.apply(message.events);
        break;
      case 'failed':
        this.drop(`Transcription stopped: ${message.message}`);
        break;
    }
  }

  private apply(events: { kind: string; text?: string; line?: FinalLine }[]) {
    for (const event of events) {
      if (event.kind === 'final' && event.line) {
        this.lines.push(event.line);
        this.events.line(event.line);
      } else if (event.kind === 'partial') {
        this.events.partial(event.text ?? '');
      }
    }
  }

  private setTranscription(state: TranscriptionState) {
    this.transcription = state;
    this.events.transcription(state);
  }

  /** Stop transcribing for the rest of this recording; the recording goes on. */
  private drop(reason: string) {
    if (!this.recognizer) return;
    this.recognizer.terminate();
    this.recognizer = null;
    this.lines = [];
    this.events.partial('');
    this.setTranscription({ kind: 'dropped', reason });
  }

  /** Stop the microphone, finish the transcript and the file; returns what to upload. */
  async stop(): Promise<Recording> {
    if (this.node) {
      const flushed = new Promise<void>((resolve) => {
        const timer = setTimeout(resolve, 1000);
        this.node!.port.onmessage = (e) => {
          const { samples, flushed: done } = e.data as { samples?: Float32Array; flushed?: boolean };
          if (samples) {
            this.writer.postMessage({ type: 'audio', samples } satisfies WriterIn);
            this.recognizer?.postMessage({ type: 'audio', samples } satisfies RecognizerIn);
          }
          if (done) {
            clearTimeout(timer);
            resolve();
          }
        };
      });
      this.node.port.postMessage('flush');
      await flushed;
    }
    this.closeMicrophone();
    let lines: FinalLine[] | null = null;
    if (this.recognizer && this.transcription.kind !== 'dropped') {
      const finished = once<RecognizerOut>(this.recognizer, ['finished', 'failed'], FINISH_TIMEOUT_MS).catch(() => null);
      this.recognizer.postMessage({ type: 'finish' } satisfies RecognizerIn);
      const answer = await finished;
      // onRecognizer has applied the last events by now; a failure or a timeout drops the transcript.
      if (answer?.type === 'finished' && this.recognizer) lines = [...this.lines];
      else this.drop('Transcription did not finish.');
    }
    this.recognizer?.terminate();
    this.recognizer = null;
    const stopped = once<WriterOut>(this.writer, ['stopped', 'failed']);
    this.writer.postMessage({ type: 'stop' } satisfies WriterIn);
    const answer = await stopped;
    this.writer.terminate();
    if (answer.type === 'failed') throw new Error(answer.message);
    if (answer.type !== 'stopped') throw new Error('The recording could not be saved.');
    return { sha256: answer.sha256, size: answer.size, durationMs: answer.durationMs, lines };
  }

  private closeMicrophone() {
    this.node?.disconnect();
    this.node = null;
    for (const track of this.stream?.getTracks() ?? []) track.stop();
    this.stream = null;
    this.context?.close().catch(() => {});
    this.context = null;
    this.wakeLock?.release().catch(() => {});
    this.wakeLock = null;
  }

  /** Give up: release everything, keep nothing. */
  abort() {
    this.closeMicrophone();
    this.recognizer?.terminate();
    this.recognizer = null;
    this.writer.terminate();
  }
}
