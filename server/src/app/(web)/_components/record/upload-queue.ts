import type { FinalLine } from './segmenter';

/** Bytes per part: what devices send (#67), under the server's 16 MB limit. */
export const PART_BYTES = 8 * 1024 * 1024;

/**
 * A recording made in the web app waiting to become a session (#164), as
 * kept in IndexedDB: what `POST /api/web/v1/sessions` needs, and how far
 * its upload got. `syncId` is chosen when recording starts, so sending the
 * create again after a lost answer finds the same session.
 */
export type QueuedRecording = {
  syncId: string;
  audioSha: string;
  size: number;
  durationMs: number;
  createdAt: number;
  language: string;
  zone: string | null;
  /** The browser model's id, or null for a recording without a transcript. */
  asrModel: string | null;
  lines: FinalLine[];
  /** The server's upload id while parts are being sent. */
  uploadId: string | null;
  /** The server has the recording; only the session is left to create. */
  stored: boolean;
  /** Why the server refused it, for good: the user must retry it by hand. */
  failed: string | null;
  queuedAt: number;
};

/** Where the queue keeps its recordings: IndexedDB in the browser, a map in tests. */
export interface QueueStore {
  all(): Promise<QueuedRecording[]>;
  put(item: QueuedRecording): Promise<void>;
  remove(syncId: string): Promise<void>;
}

export type UploadDeps = {
  fetch: typeof fetch;
  store: QueueStore;
  /** The recording's WAV, to slice parts from. */
  file(item: QueuedRecording): Promise<Blob>;
  /** Delete the recording's WAV once its session exists. */
  removeFile(item: QueuedRecording): Promise<void>;
  /** Wait `ms`, or less when `signal` aborts. */
  sleep?(ms: number, signal: AbortSignal): Promise<void>;
};

/** A refusal retrying cannot fix: the item is marked failed. */
export class FatalUpload extends Error {}

/**
 * The sign-in has expired (#165): a `401`, or a redirect to sign in from
 * authz-proxy. The queue waits, untouched, until the user signs in again.
 */
export class SignedOut extends Error {
  constructor() {
    super('signed_out');
  }
}

/** Wait `2 s × 2^(attempt-1)`, at most a minute, before trying again after a failure. */
export const backoffMs = (attempt: number) => Math.min(60_000, 2_000 * 2 ** Math.max(0, attempt - 1));

/** What the queue is doing, for the status line. */
export type QueueStatus = {
  items: QueuedRecording[];
  /** The recording being sent and how many of its bytes the server has. */
  active: { syncId: string; sent: number } | null;
  /** When the last attempt failed and the next is waiting, why. */
  waiting: string | null;
  /** The sign-in expired: nothing is sent until the user signs in again. */
  signedOut: boolean;
};

/** How long an empty queue waits before looking again, unless kicked. */
const IDLE_MS = 60_000;

const defaultSleep = (ms: number, signal: AbortSignal) =>
  new Promise<void>((resolve) => {
    const timer = setTimeout(resolve, ms);
    signal.addEventListener('abort', () => {
      clearTimeout(timer);
      resolve();
    });
  });

type Answer = { status: number; body: Record<string, unknown> };

/**
 * Sends queued recordings, oldest first: start the upload (or learn the
 * server already has it), resume from the parts it holds, send the rest in
 * 8 MB parts, complete, then create the session. Every step can be sent
 * again safely, so a network failure anywhere just means trying again:
 * after a backoff, or at once on [kick] (the browser's `online` event, a
 * new recording).
 */
export class UploadRunner {
  private wake = new AbortController();
  private listeners = new Set<(status: QueueStatus) => void>();
  private status: QueueStatus = { items: [], active: null, waiting: null, signedOut: false };
  private stopped = false;
  private loop: Promise<void> | null = null;
  private onCreated: ((item: QueuedRecording) => void) | undefined;

  constructor(private readonly deps: UploadDeps) {}

  subscribe(listener: (status: QueueStatus) => void): () => void {
    this.listeners.add(listener);
    listener(this.status);
    return () => this.listeners.delete(listener);
  }

  /** Try now: ends any wait. */
  kick() {
    this.wake.abort();
  }

  stop() {
    this.stopped = true;
    this.kick();
  }

  private emit(patch: Partial<QueueStatus>) {
    this.status = { ...this.status, ...patch };
    for (const listener of this.listeners) listener(this.status);
  }

  async refresh(): Promise<QueuedRecording[]> {
    const items = (await this.deps.store.all()).sort((a, b) => a.queuedAt - b.queuedAt);
    this.emit({ items });
    return items;
  }

  /** Run until [stop]: send what is queued, then wait for a kick. A second call joins the running loop. */
  run(onCreated?: (item: QueuedRecording) => void): Promise<void> {
    this.stopped = false;
    this.onCreated = onCreated;
    this.loop ??= this.drain().finally(() => {
      this.loop = null;
    });
    return this.loop;
  }

  private async drain(): Promise<void> {
    let attempt = 0;
    const sleep = this.deps.sleep ?? defaultSleep;
    while (!this.stopped) {
      if (this.wake.signal.aborted) this.wake = new AbortController();
      const signal = this.wake.signal;
      const next = (await this.refresh()).find((item) => !item.failed);
      if (!next) {
        await sleep(IDLE_MS, signal);
        continue;
      }
      try {
        await this.send(next);
        attempt = 0;
        this.emit({ active: null, waiting: null, signedOut: false });
        this.onCreated?.(next);
      } catch (err) {
        this.emit({ active: null });
        if (err instanceof SignedOut) {
          // Not a failure to back off from: wait for the user, looking again now and then or on a kick.
          attempt = 0;
          this.emit({ signedOut: true, waiting: null });
          await sleep(IDLE_MS, signal);
          continue;
        }
        if (err instanceof FatalUpload) {
          await this.deps.store.put({ ...next, failed: err.message });
          continue;
        }
        attempt++;
        const wait = backoffMs(attempt);
        this.emit({ waiting: err instanceof Error ? err.message : String(err), signedOut: false });
        await sleep(wait, signal);
      }
    }
  }

  /** Send one recording until its session exists. Throws to retry, or [FatalUpload]. */
  async send(start: QueuedRecording): Promise<void> {
    let item = start;
    const save = async (patch: Partial<QueuedRecording>) => {
      item = { ...item, ...patch };
      await this.deps.store.put(item);
    };
    this.emit({ active: { syncId: item.syncId, sent: 0 } });
    for (let round = 0; !item.stored; round++) {
      if (round > 5) throw new Error('upload_restarted_too_often');
      await this.upload(item, save);
    }
    const created = await this.call('POST', '/api/web/v1/sessions', {
      json: {
        syncId: item.syncId,
        audioSha: item.audioSha,
        durationMs: item.durationMs,
        createdAt: item.createdAt,
        language: item.language,
        lines: item.lines,
        zone: item.zone,
        asrModel: item.asrModel,
      },
    });
    if (created.status === 422) {
      // The server lost the recording (cleaned up meanwhile): send it again next time.
      await save({ stored: false, uploadId: null });
      throw new Error('missing_blobs');
    }
    if (created.status === 409) throw new FatalUpload('Another session already has this recording’s id.');
    if (created.status !== 200 && created.status !== 201) this.refuse(created);
    await this.deps.store.remove(item.syncId);
    await this.deps.removeFile(item).catch(() => {});
  }

  /** One pass at storing the recording on the server; leaves `stored` set, or `uploadId` cleared to start over. */
  private async upload(item: QueuedRecording, save: (patch: Partial<QueuedRecording>) => Promise<void>) {
    const blobs = `/api/web/v1/blobs/${item.audioSha}/uploads`;
    let uploadId = item.uploadId;
    if (!uploadId) {
      const started = await this.call('POST', blobs);
      if (started.status === 200 && started.body.exists) return save({ stored: true, uploadId: null });
      if (started.status !== 201 || typeof started.body.id !== 'string') this.refuse(started);
      uploadId = started.body.id as string;
      await save({ uploadId });
    }
    const upload = `${blobs}/${uploadId}`;
    const listed = await this.call('GET', upload);
    if (listed.status === 404) return save({ uploadId: null });
    if (listed.status !== 200) this.refuse(listed);
    const parts = Math.max(1, Math.ceil(item.size / PART_BYTES));
    const partSize = (n: number) => Math.min(PART_BYTES, item.size - (n - 1) * PART_BYTES);
    const have = new Set(
      ((listed.body.parts as { n: number; size: number }[]) ?? []).filter((p) => p.size === partSize(p.n)).map((p) => p.n),
    );
    let sent = [...have].reduce((sum, n) => sum + partSize(n), 0);
    this.emit({ active: { syncId: item.syncId, sent } });
    const file = await this.deps.file(item);
    if (file.size !== item.size) throw new FatalUpload('The recording on this computer is incomplete.');
    for (let n = 1; n <= parts; n++) {
      if (have.has(n)) continue;
      const body = file.slice((n - 1) * PART_BYTES, (n - 1) * PART_BYTES + partSize(n));
      const put = await this.call('PUT', `${upload}/parts/${n}`, { body });
      if (put.status === 404) return save({ uploadId: null });
      if (put.status !== 204 && put.status !== 200) this.refuse(put);
      sent += partSize(n);
      this.emit({ active: { syncId: item.syncId, sent } });
    }
    const done = await this.call('POST', `${upload}/complete`);
    // 404: the upload is gone, perhaps completed by an earlier try whose answer was lost; starting over finds out.
    if (done.status === 404) return save({ uploadId: null });
    if (done.status === 409) throw new Error('parts_missing');
    if (done.status === 400 && done.body.error === 'hash_mismatch') {
      await save({ uploadId: null });
      throw new FatalUpload('The recording on this computer does not match its checksum.');
    }
    if (done.status !== 200) this.refuse(done);
    await save({ stored: true, uploadId: null });
  }

  /** Throw for an unexpected answer: retry on 5xx and 429, give up on the rest. */
  private refuse(answer: Answer): never {
    const code = typeof answer.body.error === 'string' ? answer.body.error : `http_${answer.status}`;
    if (answer.status >= 500 && answer.status !== 507) throw new Error(code);
    if (answer.status === 429 || answer.status === 0) throw new Error(code);
    if (answer.status === 507) throw new FatalUpload('No room left for recordings on the server.');
    throw new FatalUpload(`The server refused the recording (${code}).`);
  }

  /**
   * One request; a network failure or an answer that is not the server's
   * JSON throws, to retry. Redirects are not followed: the server's routes
   * never redirect, so one is authz-proxy sending the browser to sign in.
   */
  private async call(method: string, url: string, init: { json?: unknown; body?: Blob } = {}): Promise<Answer> {
    let res: Response;
    try {
      res = await this.deps.fetch(url, {
        method,
        cache: 'no-store',
        redirect: 'manual',
        headers: init.json !== undefined ? { 'Content-Type': 'application/json' } : undefined,
        body: init.json !== undefined ? JSON.stringify(init.json) : init.body,
      });
    } catch {
      throw new Error('offline');
    }
    if (res.type === 'opaqueredirect' || res.status === 401) throw new SignedOut();
    if (res.status === 204) return { status: 204, body: {} };
    const text = await res.text().catch(() => null);
    if (text === null) throw new Error('offline');
    if (!text) return { status: res.status, body: {} };
    try {
      return { status: res.status, body: JSON.parse(text) as Record<string, unknown> };
    } catch {
      // A sign-in page or a proxy's error page, not the server's answer.
      throw new Error(`unexpected_answer_${res.status}`);
    }
  }
}
