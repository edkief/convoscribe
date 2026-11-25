/// <reference lib="webworker" />
import { BUNDLES, recognizerConfig, SAMPLE_RATE } from './asr-catalog';
import { cachedBytes, runtimeUrls } from './model-store';
import { EndpointSegmenter, type RecognizerBackend, type TranscriptEvent } from './segmenter';

/**
 * The Record page's live transcript (#164): sherpa-onnx WebAssembly running
 * the app's Kroko model, single-threaded, in its own worker so a slow
 * recognizer never holds up the recording. Everything comes from the
 * browser's cache (model-store.ts): loading makes no request.
 *
 * In: `{type:'load', bundleId}`, `{type:'audio', samples}`, `{type:'finish'}`;
 * audio sent while the model loads is held and decoded once it is ready.
 * Out: `{type:'ready', loadMs}`, `{type:'events', events, processed,
 * computeMs}` per chunk (`processed`: samples decoded so far),
 * `{type:'finished', events}`, `{type:'failed', message}`.
 */

export type RecognizerIn = { type: 'load'; bundleId: string } | { type: 'audio'; samples: Float32Array } | { type: 'finish' };
export type RecognizerOut =
  | { type: 'ready'; loadMs: number }
  | { type: 'events'; events: TranscriptEvent[]; processed: number; computeMs: number }
  | { type: 'finished'; events: TranscriptEvent[] }
  | { type: 'failed'; message: string };

/** The bits of sherpa-onnx's JS API (sherpa-onnx-asr.js) used here. */
type SherpaStream = { acceptWaveform(rate: number, samples: Float32Array): void; inputFinished(): void; free(): void };
type SherpaRecognizer = {
  createStream(): SherpaStream;
  isReady(s: SherpaStream): boolean;
  decode(s: SherpaStream): void;
  isEndpoint(s: SherpaStream): boolean;
  reset(s: SherpaStream): void;
  getResult(s: SherpaStream): { text: string; tokens: string[]; timestamps: number[] };
  free(): void;
};
type SherpaModule = {
  FS: { mkdir(path: string): void; writeFile(path: string, data: Uint8Array): void; unlink(path: string): void };
};

const scope = self as unknown as DedicatedWorkerGlobalScope;
const post = (message: RecognizerOut) => scope.postMessage(message);
let segmenter: EndpointSegmenter | null = null;
/** Audio that arrived while the model loaded, decoded once it is ready. */
let pending: Float32Array[] | null = null;
let finishWhenReady = false;

/** Import an ES module from cached source text. */
async function importCached(url: string) {
  const bytes = await cachedBytes(url);
  if (!bytes) throw new Error('The speech model is not downloaded.');
  const blobUrl = URL.createObjectURL(new Blob([bytes], { type: 'text/javascript' }));
  try {
    return await import(/* webpackIgnore: true */ /* turbopackIgnore: true */ blobUrl);
  } finally {
    URL.revokeObjectURL(blobUrl);
  }
}

async function load(bundleId: string) {
  const started = performance.now();
  const bundle = BUNDLES.find((b) => b.id === bundleId);
  if (!bundle) throw new Error(`Unknown model ${bundleId}`);
  const urls = runtimeUrls();
  const wasm = await cachedBytes(urls.wasm);
  if (!wasm) throw new Error('The speech model is not downloaded.');
  const { default: createModule } = await importCached(urls.factory);
  const { createOnlineRecognizer } = await importCached(urls.api);
  // locateFile keeps the factory from resolving the .wasm against its blob: URL; wasmBinary means it never fetches.
  const sherpa: SherpaModule = await createModule({ wasmBinary: wasm, locateFile: (path: string) => path });
  const dir = '/model';
  sherpa.FS.mkdir(dir);
  for (const file of bundle.files) {
    const bytes = await cachedBytes(file.url);
    if (!bytes) throw new Error('The speech model is not downloaded.');
    sherpa.FS.writeFile(`${dir}/${file.name}`, new Uint8Array(bytes));
  }
  const recognizer: SherpaRecognizer = createOnlineRecognizer(sherpa, recognizerConfig(dir));
  // The recognizer holds the model now; free the in-memory copies.
  for (const file of bundle.files) sherpa.FS.unlink(`${dir}/${file.name}`);
  const stream = recognizer.createStream();
  const backend: RecognizerBackend = {
    acceptWaveform: (samples) => stream.acceptWaveform(SAMPLE_RATE, samples),
    decodeAvailable: () => {
      while (recognizer.isReady(stream)) recognizer.decode(stream);
    },
    currentText: () => recognizer.getResult(stream).text,
    currentTokens: () => {
      const { tokens, timestamps } = recognizer.getResult(stream);
      const count = Math.min(tokens.length, timestamps.length);
      return Array.from({ length: count }, (_, i) => ({ text: tokens[i], ms: Math.round(timestamps[i] * 1000) }));
    },
    isEndpoint: () => recognizer.isEndpoint(stream),
    reset: () => recognizer.reset(stream),
    inputFinished: () => stream.inputFinished(),
  };
  segmenter = new EndpointSegmenter(backend);
  post({ type: 'ready', loadMs: Math.round(performance.now() - started) });
  const held = pending ?? [];
  pending = null;
  for (const samples of held) decode(samples);
  if (finishWhenReady) finish();
}

function decode(samples: Float32Array) {
  if (!segmenter) {
    pending?.push(samples);
    return;
  }
  const started = performance.now();
  const events = segmenter.accept(samples);
  post({ type: 'events', events, processed: segmenter.totalSamples, computeMs: performance.now() - started });
}

function finish() {
  if (pending) {
    finishWhenReady = true;
    return;
  }
  post({ type: 'finished', events: segmenter ? segmenter.finish() : [] });
}

function fail(err: unknown) {
  segmenter = null;
  pending = null;
  post({ type: 'failed', message: err instanceof Error ? err.message : String(err) });
}

scope.onmessage = (e: MessageEvent<RecognizerIn>) => {
  const message = e.data;
  try {
    if (message.type === 'load') {
      pending = [];
      load(message.bundleId).catch(fail);
    } else if (message.type === 'audio') {
      decode(message.samples);
    } else if (message.type === 'finish') {
      finish();
    }
  } catch (err) {
    fail(err);
  }
};
