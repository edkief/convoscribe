import type { Draft } from './recovery';
import type { FinalLine } from './segmenter';
import type { QueuedRecording, QueueStore } from './upload-queue';

const DB_NAME = 'convoscribe-web';
const UPLOADS = 'uploads';
/** Recordings in progress (#165): what recovering one after a crash needs. */
const DRAFTS = 'drafts';
/** Their finished transcript lines, one row each, so saving a line never rewrites the others. */
const DRAFT_LINES = 'draftLines';

type DraftLineRow = { syncId: string; startMs: number; line: FinalLine };

let db: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  db ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 2);
    request.onupgradeneeded = (e) => {
      const conn = request.result;
      if (e.oldVersion < 1) conn.createObjectStore(UPLOADS, { keyPath: 'syncId' });
      if (e.oldVersion < 2) {
        conn.createObjectStore(DRAFTS, { keyPath: 'syncId' });
        conn.createObjectStore(DRAFT_LINES, { keyPath: ['syncId', 'startMs'] });
      }
    };
    request.onsuccess = () => {
      const conn = request.result;
      // A newer version of the page in another tab wants to upgrade: let it, and open again next time.
      conn.onversionchange = () => {
        conn.close();
        db = null;
      };
      resolve(conn);
    };
    request.onerror = () => {
      db = null;
      reject(request.error);
    };
  });
  return db;
}

/** Run `body` in one transaction over `stores`; resolves with what its last request returned, once committed. */
async function run<T>(
  stores: string[],
  mode: IDBTransactionMode,
  body: (tx: IDBTransaction) => IDBRequest<T> | void,
): Promise<T> {
  const tx = (await open()).transaction(stores, mode);
  const request = body(tx);
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve(request ? request.result : (undefined as T));
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

const linesOf = (syncId: string) => IDBKeyRange.bound([syncId, -Infinity], [syncId, Infinity]);

/** The upload queue's recordings in IndexedDB (#164), kept across pages and reloads. */
export const indexedDbQueue: QueueStore = {
  all: () => run([UPLOADS], 'readonly', (tx) => tx.objectStore(UPLOADS).getAll() as IDBRequest<QueuedRecording[]>),
  put: async (item) => {
    await run([UPLOADS], 'readwrite', (tx) => tx.objectStore(UPLOADS).put(item));
  },
  remove: async (syncId) => {
    await run([UPLOADS], 'readwrite', (tx) => tx.objectStore(UPLOADS).delete(syncId));
  },
};

/** Note a recording as started, before its file is opened (#165). */
export async function putDraft(draft: Draft): Promise<void> {
  await run([DRAFTS], 'readwrite', (tx) => tx.objectStore(DRAFTS).put(draft));
}

/** Keep a finished transcript line of a recording in progress. */
export async function appendDraftLine(syncId: string, line: FinalLine): Promise<void> {
  const row: DraftLineRow = { syncId, startMs: line.startMs, line };
  await run([DRAFT_LINES], 'readwrite', (tx) => tx.objectStore(DRAFT_LINES).put(row));
}

/** The live transcript was stopped: the recording goes on, and is recovered, without one. */
export async function markDraftDropped(syncId: string): Promise<void> {
  await run([DRAFTS, DRAFT_LINES], 'readwrite', (tx) => {
    const drafts = tx.objectStore(DRAFTS);
    const get = drafts.get(syncId) as IDBRequest<Draft | undefined>;
    get.onsuccess = () => {
      if (get.result) drafts.put({ ...get.result, transcriptDropped: true });
    };
    tx.objectStore(DRAFT_LINES).delete(linesOf(syncId));
  });
}

export const listDrafts = () => run([DRAFTS], 'readonly', (tx) => tx.objectStore(DRAFTS).getAll() as IDBRequest<Draft[]>);

/** A draft's saved lines, in order. */
export async function draftLines(syncId: string): Promise<FinalLine[]> {
  const rows = await run([DRAFT_LINES], 'readonly', (tx) => tx.objectStore(DRAFT_LINES).getAll(linesOf(syncId)) as IDBRequest<DraftLineRow[]>);
  return rows.map((row) => row.line);
}

/** Queue a finished recording and forget its draft, in one transaction: a crash leaves one or the other. */
export async function promoteDraft(item: QueuedRecording): Promise<void> {
  await run([UPLOADS, DRAFTS, DRAFT_LINES], 'readwrite', (tx) => {
    tx.objectStore(UPLOADS).put(item);
    tx.objectStore(DRAFTS).delete(item.syncId);
    tx.objectStore(DRAFT_LINES).delete(linesOf(item.syncId));
  });
}

/** Forget a draft and its lines. */
export async function discardDraft(syncId: string): Promise<void> {
  await run([DRAFTS, DRAFT_LINES], 'readwrite', (tx) => {
    tx.objectStore(DRAFTS).delete(syncId);
    tx.objectStore(DRAFT_LINES).delete(linesOf(syncId));
  });
}
