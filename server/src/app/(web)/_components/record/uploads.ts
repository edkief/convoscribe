'use client';

import { listRecordings, recordingFile, removeRecording } from './opfs';
import { discardDraft, draftLines, indexedDbQueue, listDrafts, promoteDraft } from './queue-store';
import { interruptedDrafts, MIN_RECOVERED_MS, orphans, recordingLock, recoveredItem, type Draft } from './recovery';
import { UploadRunner, type QueuedRecording } from './upload-queue';
import { pcmDurationMs, recoveredDataBytes } from './wav';
import type { WriterIn, WriterOut } from './writer.worker';

/** Tabs tell each other the queue changed, so every tab's status follows. */
const CHANNEL = 'convoscribe-uploads';
/** One tab sends at a time. */
export const UPLOAD_LOCK = 'convoscribe-uploads';

let runner: UploadRunner | null = null;
let channel: BroadcastChannel | null = null;
const changeListeners = new Set<() => void>();

/** This tab's upload runner (#164), over IndexedDB and the recordings in OPFS. */
export function uploadRunner(): UploadRunner {
  if (!runner) {
    runner = new UploadRunner({
      fetch: (input, init) => fetch(input, init),
      store: indexedDbQueue,
      file: (item) =>
        recordingFile(item.syncId).catch((err) => {
          if (err instanceof DOMException && err.name === 'NotFoundError') return null;
          throw err;
        }),
      removeFile: (item) => removeRecording(item.syncId),
    });
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel(CHANNEL);
      channel.onmessage = () => {
        runner!.refresh().catch(() => {});
        runner!.kick();
        for (const listener of changeListeners) listener();
      };
    }
  }
  return runner;
}

/** Tell this tab and the others that the queue or the drafts changed. */
export function announce() {
  channel?.postMessage('changed');
  for (const listener of changeListeners) listener();
}

/** Call `listener` whenever [announce] runs in any tab. */
export function onChange(listener: () => void): () => void {
  uploadRunner();
  changeListeners.add(listener);
  return () => changeListeners.delete(listener);
}

/** Queue a finished recording, forgetting its draft (#165), and start sending it. */
export async function enqueue(item: QueuedRecording): Promise<void> {
  const r = uploadRunner();
  await promoteDraft(item);
  await r.refresh();
  announce();
  r.kick();
}

/** Try a failed recording again. */
export async function retry(item: QueuedRecording): Promise<void> {
  await enqueue({ ...item, failed: null, lastError: null });
}

/** Delete a queued recording that will not be uploaded (#165): from the queue, then its file. */
export async function discard(item: QueuedRecording): Promise<void> {
  await indexedDbQueue.remove(item.syncId);
  await removeRecording(item.syncId);
  await uploadRunner().refresh();
  announce();
}

/**
 * Remove what a crash can leave behind (#165): recordings stored in this
 * browser that are neither queued nor in progress, and drafts whose
 * recording is gone. Run by the tab that sends, as it starts.
 */
export async function sweep(): Promise<void> {
  const files = await listRecordings();
  const queued = (await indexedDbQueue.all()).map((item) => item.syncId);
  const { files: stale, drafts } = orphans(files, queued, await listDrafts(), await heldLocks());
  for (const syncId of stale) await removeRecording(syncId);
  for (const syncId of drafts) await discardDraft(syncId);
  if (stale.length || drafts.length) announce();
}

/** Throw away a recording that was never queued: its WAV and its draft. */
export async function discardRecording(syncId: string): Promise<void> {
  await Promise.all([removeRecording(syncId), discardDraft(syncId).catch(() => {})]);
}

/**
 * Take the Web Lock `name` and keep it until the returned function is
 * called. Resolves once it is held; with `ifAvailable`, resolves null when
 * another tab holds it. Without Web Locks, every lock is free.
 */
export function holdLock(name: string, options: { ifAvailable?: boolean } = {}): Promise<(() => void) | null> {
  if (typeof navigator === 'undefined' || !navigator.locks) return Promise.resolve(() => {});
  return new Promise((acquired) => {
    navigator.locks
      .request(name, { ifAvailable: options.ifAvailable ?? false }, (lock) => {
        if (!lock) {
          acquired(null);
          return;
        }
        return new Promise<void>((release) => acquired(release));
      })
      .catch(() => acquired(null));
  });
}

/** The Web Locks some tab holds now. */
async function heldLocks(): Promise<Set<string>> {
  if (typeof navigator === 'undefined' || !navigator.locks) return new Set();
  const { held = [] } = await navigator.locks.query();
  return new Set(held.map((lock) => lock.name ?? ''));
}

/** A recording cut short by a reload or a crash (#165), and about how long its stored audio is. */
export type Interrupted = { draft: Draft; durationMs: number };

/**
 * The recordings no tab is recording but that never reached the queue.
 * A draft whose file is gone is forgotten; one whose file another tab has
 * open (a browser without Web Locks) is left alone.
 */
export async function interruptedRecordings(): Promise<Interrupted[]> {
  const drafts = interruptedDrafts(await listDrafts(), await heldLocks());
  const found: Interrupted[] = [];
  for (const draft of drafts) {
    try {
      const file = await recordingFile(draft.syncId);
      found.push({ draft, durationMs: pcmDurationMs(recoveredDataBytes(file.size)) });
    } catch (err) {
      if (err instanceof DOMException && err.name === 'NotFoundError') await discardDraft(draft.syncId);
    }
  }
  return found;
}

/**
 * Finish an interrupted recording and queue it: its WAV is cut to the
 * last whole sample and given its sizes, its transcript to the lines saved
 * within that audio. One too short to keep is thrown away. Returns whether
 * it was queued.
 */
export async function recover(draft: Draft): Promise<boolean> {
  const release = await holdLock(recordingLock(draft.syncId), { ifAvailable: true });
  if (!release) throw new Error('Another tab is recovering it.');
  try {
    const writer = new Worker(new URL('./writer.worker.ts', import.meta.url), { type: 'module', name: 'recording-recovery' });
    const answer = await new Promise<WriterOut>((resolve) => {
      writer.onmessage = (e: MessageEvent<WriterOut>) => resolve(e.data);
      writer.onerror = () => resolve({ type: 'failed', message: 'The recording could not be read.' });
      writer.postMessage({ type: 'recover', syncId: draft.syncId } satisfies WriterIn);
    }).finally(() => writer.terminate());
    if (answer.type !== 'stopped') throw new Error(answer.type === 'failed' ? answer.message : 'The recording could not be read.');
    if (answer.durationMs < MIN_RECOVERED_MS) {
      await discardRecording(draft.syncId);
      announce();
      return false;
    }
    const lines = await draftLines(draft.syncId);
    await enqueue(recoveredItem(draft, lines, answer, Date.now()));
    return true;
  } finally {
    release();
  }
}

/** Throw away an interrupted recording. */
export async function discardInterrupted(draft: Draft): Promise<void> {
  const release = await holdLock(recordingLock(draft.syncId), { ifAvailable: true });
  if (!release) throw new Error('Another tab is using it.');
  try {
    await discardRecording(draft.syncId);
    announce();
  } finally {
    release();
  }
}
