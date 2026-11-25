import type { QueuedRecording, QueueStore } from './upload-queue';

const DB_NAME = 'convoscribe-web';
const STORE = 'uploads';

let db: Promise<IDBDatabase> | null = null;

function open(): Promise<IDBDatabase> {
  db ??= new Promise((resolve, reject) => {
    const request = indexedDB.open(DB_NAME, 1);
    request.onupgradeneeded = () => request.result.createObjectStore(STORE, { keyPath: 'syncId' });
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => {
      db = null;
      reject(request.error);
    };
  });
  return db;
}

async function run<T>(mode: IDBTransactionMode, body: (store: IDBObjectStore) => IDBRequest<T>): Promise<T> {
  const tx = (await open()).transaction(STORE, mode);
  const request = body(tx.objectStore(STORE));
  return new Promise((resolve, reject) => {
    tx.oncomplete = () => resolve(request.result);
    tx.onerror = () => reject(tx.error);
    tx.onabort = () => reject(tx.error);
  });
}

/** The upload queue's recordings in IndexedDB (#164), kept across pages (and, with #165, reloads). */
export const indexedDbQueue: QueueStore = {
  all: () => run('readonly', (s) => s.getAll() as IDBRequest<QueuedRecording[]>),
  put: async (item) => {
    await run('readwrite', (s) => s.put(item));
  },
  remove: async (syncId) => {
    await run('readwrite', (s) => s.delete(syncId));
  },
};
