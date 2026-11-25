/** Recordings made in the web app (#164) live here in the browser's private storage until uploaded. */
export const RECORDINGS_DIR = 'recordings';

export const recordingFileName = (syncId: string) => `${syncId}.wav`;

/** The parts of `FileSystemSyncAccessHandle` the writer uses (worker-only, so not in the DOM types). */
export interface SyncAccessHandle {
  read(buffer: Uint8Array, options?: { at?: number }): number;
  write(buffer: Uint8Array, options?: { at?: number }): number;
  truncate(size: number): void;
  getSize(): number;
  flush(): void;
  close(): void;
}

async function recordings() {
  const root = await navigator.storage.getDirectory();
  return root.getDirectoryHandle(RECORDINGS_DIR, { create: true });
}

/** A stored recording's WAV. */
export async function recordingFile(syncId: string): Promise<File> {
  const file = await (await recordings()).getFileHandle(recordingFileName(syncId));
  return file.getFile();
}

/** The `syncId`s of the recordings stored in this browser (#165). */
export async function listRecordings(): Promise<string[]> {
  const dir = (await recordings()) as unknown as { keys(): AsyncIterable<string> };
  const ids: string[] = [];
  for await (const name of dir.keys()) {
    if (name.endsWith('.wav')) ids.push(name.slice(0, -'.wav'.length));
  }
  return ids;
}

/** Delete a stored recording; nothing when it is already gone. */
export async function removeRecording(syncId: string): Promise<void> {
  await (await recordings()).removeEntry(recordingFileName(syncId)).catch(() => {});
}

/** Whether the browser has what recording needs: OPFS with sync handles in workers, an AudioWorklet. */
export function recordingSupported(): boolean {
  return (
    typeof navigator !== 'undefined' &&
    !!navigator.storage?.getDirectory &&
    !!navigator.mediaDevices?.getUserMedia &&
    typeof AudioWorkletNode !== 'undefined' &&
    typeof indexedDB !== 'undefined'
  );
}
