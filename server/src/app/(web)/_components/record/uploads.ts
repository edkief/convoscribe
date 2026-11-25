'use client';

import { recordingFile, removeRecording } from './opfs';
import { discardDraft, indexedDbQueue, promoteDraft } from './queue-store';
import { UploadRunner, type QueuedRecording } from './upload-queue';

/** Tabs tell each other the queue changed, so every tab's status follows. */
const CHANNEL = 'convoscribe-uploads';
/** One tab sends at a time. */
export const UPLOAD_LOCK = 'convoscribe-uploads';

let runner: UploadRunner | null = null;
let channel: BroadcastChannel | null = null;

/** This tab's upload runner (#164), over IndexedDB and the recordings in OPFS. */
export function uploadRunner(): UploadRunner {
  if (!runner) {
    runner = new UploadRunner({
      fetch: (input, init) => fetch(input, init),
      store: indexedDbQueue,
      file: (item) => recordingFile(item.syncId),
      removeFile: (item) => removeRecording(item.syncId),
    });
    if (typeof BroadcastChannel !== 'undefined') {
      channel = new BroadcastChannel(CHANNEL);
      channel.onmessage = () => {
        runner!.refresh().catch(() => {});
        runner!.kick();
      };
    }
  }
  return runner;
}

/** Tell the other tabs the queue changed. */
export function announce() {
  channel?.postMessage('changed');
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
  await enqueue({ ...item, failed: null });
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
