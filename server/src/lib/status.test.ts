import { describe, expect, it, vi } from 'vitest';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { uploadParts, uploads, documents } from '@/db/schema';
import { eq } from 'drizzle-orm';
import { blobStore, setBlobStore, type BlobStore } from './blob-store';
import { db, setDb, type Db } from './db';
import { deleteDocument, purgeTrash } from './documents';
import { checkStore, serverStatus, storageBreakdown } from './status';

setupSyncServer();

const manifestSize = async (syncId: string) =>
  (await db().select({ size: documents.manifestSize }).from(documents).where(eq(documents.syncId, syncId)))[0].size!;

describe('storageBreakdown (#82)', () => {
  it('is all zero with nothing stored', async () => {
    expect(await storageBreakdown()).toEqual({ live: 0, trash: 0, unreferenced: 0, uploads: 0, total: 0 });
  });

  it('splits what every user stores by what it is for', async () => {
    const liveAudio = await storeBlob('alice', 'a live recording');
    const shared = await storeBlob('alice', 'shared with the trash');
    const trashAudio = await storeBlob('alice', 'a trashed recording!');
    const orphan = await storeBlob('bob', 'nothing uses this');
    await pushManifest('alice', sessionManifest('s1', { audio: liveAudio, clean: shared }));
    const gone = await pushManifest('alice', sessionManifest('s2', { audio: trashAudio, clean: shared }));
    await deleteDocument('alice', 'session', 's2', gone.revision);
    await db().insert(uploads).values({ id: 'u1', owner: 'bob', sha256: 'a'.repeat(64), storeUploadId: 'x' });
    await db().insert(uploadParts).values({ uploadId: 'u1', n: 1, size: 700, etag: 'e' });

    const expected = {
      live: liveAudio.size + shared.size + (await manifestSize('s1')),
      trash: trashAudio.size + (await manifestSize('s2')),
      unreferenced: orphan.size,
      uploads: 700,
    };
    expect(await storageBreakdown()).toEqual({
      ...expected,
      total: expected.live + expected.trash + expected.unreferenced + expected.uploads,
    });
  });

  it('counts a purged session’s recordings as unreferenced', async () => {
    const audio = await storeBlob('alice', 'a recording');
    const gone = await pushManifest('alice', sessionManifest('s1', { audio }));
    await deleteDocument('alice', 'session', 's1', gone.revision);
    await purgeTrash({ owner: 'alice' });
    expect(await storageBreakdown()).toMatchObject({ live: 0, trash: 0, unreferenced: audio.size });
  });
});

describe('serverStatus (#82)', () => {
  const failing = (message: string) => {
    const real = blobStore();
    return new Proxy(real, {
      get: (target, key) => (key === 'check' ? () => Promise.reject(new Error(message)) : Reflect.get(target, key)),
    }) as BlobStore;
  };

  it('reports both reachable, with the storage figures', async () => {
    const status = await serverStatus();
    expect(status.postgres.ok).toBe(true);
    expect(status.s3.ok).toBe(true);
    expect(status.storage).toMatchObject({ total: 0 });
  });

  it('keeps a store’s error out of what it returns', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const real = blobStore();
    setBlobStore(failing('http://id:s3cret@s3.internal:9000 refused'));
    const status = await serverStatus();
    setBlobStore(real);
    expect(status.s3.ok).toBe(false);
    expect(status.postgres.ok).toBe(true);
    expect(JSON.stringify(status)).not.toMatch(/s3cret|internal|refused/);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });

  it('gives up on a store that does not answer', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] });
    const real = blobStore();
    setBlobStore(new Proxy(real, { get: (t, k) => (k === 'check' ? () => new Promise(() => {}) : Reflect.get(t, k)) }));
    const pending = checkStore();
    await vi.advanceTimersByTimeAsync(5001);
    expect((await pending).ok).toBe(false);
    setBlobStore(real);
    vi.useRealTimers();
    warn.mockRestore();
  });

  it('reports Postgres down, with no storage figures and no driver error', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const real = db();
    setDb({ execute: () => Promise.reject(new Error('password authentication failed for user admin')) } as unknown as Db);
    const status = await serverStatus();
    setDb(real);
    expect(status).toMatchObject({ postgres: { ok: false }, s3: { ok: true }, storage: null });
    expect(JSON.stringify(status)).not.toMatch(/password|admin/);
    warn.mockRestore();
  });
});
