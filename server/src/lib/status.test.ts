import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, describe, expect, it, vi } from 'vitest';
import { resetAiProxy } from './ai-proxy';
import { stubServerEnv } from '@/test/env';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { uploadParts, uploads, documents } from '@/db/schema';
import { eq } from 'drizzle-orm';
import { blobStore, setBlobStore, type BlobStore } from './blob-store';
import { db, setDb, type Db } from './db';
import { deleteDocument, purgeTrash } from './documents';
import { checkAuthzProxy, checkSetup, checkStore, checkUpstream, isStatusCheck, runCheck, serverStatus, storageBreakdown } from './status';

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

describe('upstreams on the status page (#145)', () => {
  let server: Server;
  let base = '';
  let seen: { url?: string; authorization?: string }[] = [];
  /** What the fake upstream answers each path with. */
  let statusOf: (url: string) => number = () => 200;

  beforeAll(async () => {
    server = createServer((req, res) => {
      seen.push({ url: req.url, authorization: req.headers.authorization });
      res.writeHead(statusOf(req.url ?? ''), { 'content-type': 'application/json' });
      res.end('{}');
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    await resetAiProxy();
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });

  const configure = (overrides: Record<string, string> = {}) => {
    seen = [];
    statusOf = () => 200;
    stubServerEnv({
      LLM_UPSTREAM_URL: `${base}/llm/v1`,
      LLM_UPSTREAM_KEY: 'llm-key',
      LLM_MODEL: 'muse',
      ASR_UPSTREAM_URL: `${base}/asr/v1`,
      DECISION_UPSTREAM_URL: `${base}/laya`,
      ...overrides,
    });
  };

  it('asks each upstream a cheap question, with its key, and names its model', async () => {
    configure();
    const status = await serverStatus();
    expect(status.llm).toMatchObject({ ok: true, model: 'muse' });
    expect(status.asr).toMatchObject({ ok: true, model: null });
    expect(status.decision).toMatchObject({ ok: true, model: null });
    expect(status.authzProxy).toBeNull();
    expect(seen).toEqual(
      expect.arrayContaining([
        { url: '/llm/v1/models', authorization: 'Bearer llm-key' },
        { url: '/asr/v1/models', authorization: undefined },
        { url: '/laya/health', authorization: undefined },
      ]),
    );
  });

  it('is null for an upstream the server is not set up with', async () => {
    configure({ ASR_UPSTREAM_URL: '', DECISION_UPSTREAM_URL: '' });
    const status = await serverStatus();
    expect(status.asr).toBeNull();
    expect(status.decision).toBeNull();
    expect(status.llm?.ok).toBe(true);
  });

  it('counts a refused key or an error as unreachable, and says why only in the log', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    configure();
    statusOf = (url) => (url.startsWith('/llm') ? 401 : 500);
    expect(await checkUpstream('llm')).toMatchObject({ ok: false, model: 'muse' });
    expect(await checkUpstream('decision')).toMatchObject({ ok: false });
    expect(warn.mock.calls.flat().join(' ')).toMatch(/language model unreachable: answered 401, check its key/);
    warn.mockRestore();
  });

  it('counts an upstream nothing listens on as unreachable', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    configure({ LLM_UPSTREAM_URL: 'http://127.0.0.1:1/v1' });
    expect((await checkUpstream('llm'))?.ok).toBe(false);
    warn.mockRestore();
  });

  it('checks authz-proxy’s keys in that mode only', async () => {
    configure({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: base, SYNC_API_KEY: '' });
    expect((await checkAuthzProxy())?.ok).toBe(true);
    expect(seen).toContainEqual({ url: '/.well-known/jwks.json', authorization: undefined });
    configure();
    expect(await checkAuthzProxy()).toBeNull();
  });

  it('runs one check by name, as the status page asks for them', async () => {
    configure({ DECISION_UPSTREAM_URL: '' });
    expect(await runCheck('postgres')).toMatchObject({ ok: true });
    expect(await runCheck('s3')).toMatchObject({ ok: true });
    expect(await runCheck('llm')).toMatchObject({ ok: true, model: 'muse' });
    expect(await runCheck('decision')).toBeNull();
    expect(await runCheck('authz-proxy')).toBeNull();
    expect(seen.map((s) => s.url)).toEqual(['/llm/v1/models']);
    expect(isStatusCheck('asr')).toBe(true);
    expect(isStatusCheck('storage')).toBe(false);
  });

  it('says what is set up, and each model, without asking anything', async () => {
    configure({ DECISION_UPSTREAM_URL: '', ASR_MODEL: 'parakeet' });
    expect(checkSetup()).toEqual([
      { name: 'postgres', configured: true, model: null },
      { name: 's3', configured: true, model: null },
      { name: 'authz-proxy', configured: false, model: null },
      { name: 'llm', configured: true, model: 'muse' },
      { name: 'asr', configured: true, model: 'parakeet' },
      { name: 'decision', configured: false, model: null },
    ]);
    expect(seen).toEqual([]);
  });
});
