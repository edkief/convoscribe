'use client';

import { recordingFile, removeRecording } from './opfs';
import { indexedDbQueue } from './queue-store';
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

/** Queue a finished recording and start sending it. */
export async function enqueue(item: QueuedRecording): Promise<void> {
  const r = uploadRunner();
  await indexedDbQueue.put(item);
  await r.refresh();
  announce();
  r.kick();
}

/** Try a failed recording again. */
export async function retry(item: QueuedRecording): Promise<void> {
  await enqueue({ ...item, failed: null });
}
