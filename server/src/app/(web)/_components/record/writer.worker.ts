/// <reference lib="webworker" />
import { createSHA256 } from 'hash-wasm';
import { RECORDINGS_DIR, recordingFileName, type SyncAccessHandle } from './opfs';
import { pcmDurationMs, toPcm16, WAV_HEADER_BYTES, wavHeader } from './wav';

/**
 * Writes the Record page's recording (#164) to the browser's private
 * storage (OPFS) as it arrives: the app's WAV, header first with its sizes
 * filled in at Stop. A sync access handle, which Safari only offers in a
 * worker, writes in place with no copy on close.
 *
 * At Stop it reads the file back to hash it: the header's sizes are only
 * known then and SHA-256 cannot be prefixed, and reading it back also
 * checks the storage kept every byte.
 *
 * In: `{type:'start', syncId}`, `{type:'audio', samples}` (16 kHz floats),
 * `{type:'stop'}`. Out: `{type:'started'}`, `{type:'stopped', sha256,
 * size, durationMs}`, `{type:'failed', message}`.
 */

export type WriterIn = { type: 'start'; syncId: string } | { type: 'audio'; samples: Float32Array } | { type: 'stop' };
export type WriterOut =
  | { type: 'started' }
  | { type: 'stopped'; sha256: string; size: number; durationMs: number }
  | { type: 'failed'; message: string };

/** Flush to disk about every 5 s of audio, so a crash loses little (#165 recovers it). */
const FLUSH_BYTES = 5 * 16_000 * 2;
const READ_BYTES = 1024 * 1024;

const scope = self as unknown as DedicatedWorkerGlobalScope;
let handle: SyncAccessHandle | null = null;
let dataBytes = 0;
let unflushed = 0;
let failed = false;

const post = (message: WriterOut) => scope.postMessage(message);

function fail(err: unknown) {
  failed = true;
  post({ type: 'failed', message: err instanceof Error ? err.message : String(err) });
}

async function start(syncId: string) {
  const root = await navigator.storage.getDirectory();
  const dir = await root.getDirectoryHandle(RECORDINGS_DIR, { create: true });
  const file = await dir.getFileHandle(recordingFileName(syncId), { create: true });
  handle = await (file as unknown as { createSyncAccessHandle(): Promise<SyncAccessHandle> }).createSyncAccessHandle();
  handle.truncate(0);
  handle.write(wavHeader(0), { at: 0 });
  dataBytes = 0;
  unflushed = 0;
  failed = false;
  post({ type: 'started' });
}

function append(samples: Float32Array) {
  if (!handle || failed) return;
  const bytes = toPcm16(samples);
  const written = handle.write(bytes, { at: WAV_HEADER_BYTES + dataBytes });
  if (written !== bytes.length) throw new Error('The browser stopped storing the recording (disk full?).');
  dataBytes += written;
  unflushed += written;
  if (unflushed >= FLUSH_BYTES) {
    handle.flush();
    unflushed = 0;
  }
}

async function stop() {
  if (!handle) throw new Error('Not recording.');
  const h = handle;
  handle = null;
  try {
    h.write(wavHeader(dataBytes), { at: 0 });
    h.flush();
    const size = h.getSize();
    if (size !== WAV_HEADER_BYTES + dataBytes) throw new Error('The stored recording is incomplete.');
    const hasher = await createSHA256();
    hasher.init();
    const buffer = new Uint8Array(READ_BYTES);
    for (let at = 0; at < size; ) {
      const read = h.read(buffer, { at });
      if (read <= 0) throw new Error('The stored recording could not be read back.');
      hasher.update(buffer.subarray(0, read));
      at += read;
    }
    post({ type: 'stopped', sha256: hasher.digest('hex'), size, durationMs: pcmDurationMs(dataBytes) });
  } finally {
    h.close();
  }
}

scope.onmessage = (e: MessageEvent<WriterIn>) => {
  const message = e.data;
  try {
    if (message.type === 'start') start(message.syncId).catch(fail);
    else if (message.type === 'audio') append(message.samples);
    else if (message.type === 'stop') stop().catch(fail);
  } catch (err) {
    fail(err);
  }
};
