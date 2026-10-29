import { createAuthzDevServer, type AuthzDevServer } from '@authz-proxy/sdk/dev';
import { gzipSync } from 'node:zlib';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { stubServerEnv } from '@/test/env';
import { ctx, request, sha256, setupSyncServer } from '@/test/http';
import { POST as startUpload } from '../../../blobs/[sha]/uploads/route';
import { PUT as putPart } from '../../../blobs/[sha]/uploads/[id]/parts/[n]/route';
import { POST as complete } from '../../../blobs/[sha]/uploads/[id]/complete/route';
import { GET as changes } from '../../../changes/route';
import { DELETE, GET, PUT } from './route';

setupSyncServer();

const ID = '6f1c2b8e-1d4a-4c51-9b7e-0d2f3a4b5c6d';

const manifest = (title: string, audio?: { sha256: string; size: number }) =>
  JSON.stringify({ schema: 1, syncId: ID, title, durationMs: 1000, audio: audio ?? null, lines: [] });

type PushOpts = { gzip?: boolean; hash?: string; syncId?: string; durationMs?: number; changedAt?: number };

function push(body: string, baseRevision: number, opts: PushOpts = {}) {
  const syncId = opts.syncId ?? ID;
  const headers: Record<string, string> = {
    'sync-base-revision': String(baseRevision),
    'sync-manifest-hash': opts.hash ?? sha256(body),
    'sync-duration-ms': String(opts.durationMs ?? 1000),
    'sync-changed-at': String(opts.changedAt ?? 1700000000000),
  };
  if (opts.gzip) headers['content-encoding'] = 'gzip';
  const bytes = opts.gzip ? gzipSync(body) : Buffer.from(body);
  return PUT(request(`/documents/session/${syncId}`, { method: 'PUT', body: bytes, headers }), ctx({ kind: 'session', syncId }));
}

const get = (syncId = ID) => GET(request(`/documents/session/${syncId}`), ctx({ kind: 'session', syncId }));

const remove = (baseRevision: number, deletedDurationMs?: number) =>
  DELETE(
    request(`/documents/session/${ID}`, {
      method: 'DELETE',
      headers: {
        'sync-base-revision': String(baseRevision),
        ...(deletedDurationMs === undefined ? {} : { 'sync-duration-ms': String(deletedDurationMs) }),
      },
    }),
    ctx({ kind: 'session', syncId: ID }),
  );

const feed = async (since = 0, limit = 100) =>
  (await changes(request(`/changes?since=${since}&limit=${limit}`))).json() as Promise<{
    changes: { kind: string; syncId: string; revision: number; seq: number; deleted: boolean }[];
    next: number;
    more: boolean;
  }>;

async function storeBlob(bytes: Uint8Array) {
  const sha = sha256(bytes);
  const { id } = await (await startUpload(request(`/blobs/${sha}/uploads`, { method: 'POST' }), ctx({ sha }))).json();
  await putPart(request(`/blobs/${sha}/uploads/${id}/parts/1`, { method: 'PUT', body: Buffer.from(bytes) }), ctx({ sha, id, n: '1' }));
  await complete(request(`/blobs/${sha}/uploads/${id}/complete`, { method: 'POST' }), ctx({ sha, id }));
  return { sha256: sha, size: bytes.length };
}

describe('documents', () => {
  it('creates a document and returns its manifest', async () => {
    const body = manifest('First');
    const res = await push(body, 0);
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ revision: 1, manifestHash: sha256(body), deleted: false });
    const got = await get();
    expect(got.status).toBe(200);
    expect(got.headers.get('sync-revision')).toBe('1');
    expect(await got.text()).toBe(body);
  });

  it('accepts a gzipped manifest', async () => {
    const body = manifest('Zipped');
    expect((await push(body, 0, { gzip: true })).status).toBe(200);
    expect(await (await get()).text()).toBe(body);
  });

  it('refuses a stale push that loses the conflict rule, with the current version', async () => {
    await push(manifest('One'), 0);
    expect((await (await push(manifest('Two'), 1)).json()).revision).toBe(2);
    const stale = await push(manifest('Three'), 1, { changedAt: 1600000000000 });
    expect(stale.status).toBe(409);
    expect(await stale.json()).toMatchObject({ error: 'conflict', current: { revision: 2, manifestHash: sha256(manifest('Two')) } });
  });

  it('accepts a stale push with a longer recording as the next revision', async () => {
    await push(manifest('One'), 0);
    await push(manifest('Renamed'), 1, { changedAt: 1800000000000 });
    const longer = await push(manifest('Continued'), 1, { durationMs: 5000 });
    expect(longer.status).toBe(200);
    expect(await longer.json()).toMatchObject({ revision: 3, durationMs: 5000 });
    expect((await (await get()).json()).title).toBe('Continued');
  });

  it('accepts a stale push of equal length that changed later', async () => {
    await push(manifest('One'), 0);
    await push(manifest('Earlier'), 1, { changedAt: 1700000000100 });
    expect((await push(manifest('Later'), 1, { changedAt: 1700000000200 })).status).toBe(200);
    expect((await (await get()).json()).title).toBe('Later');
  });

  it('settles two concurrent pushes from the same base on the same winner', async () => {
    await push(manifest('Base'), 0);
    const [a, b] = [manifest('A'), manifest('B')];
    await Promise.all([push(a, 1), push(b, 1)]);
    // Same length and change time: the higher hash wins, whichever came first.
    const winner = sha256(a) > sha256(b) ? 'A' : 'B';
    expect((await (await get()).json()).title).toBe(winner);
  });

  it('treats a repeated push of the current manifest as done', async () => {
    await push(manifest('Same'), 0);
    const retry = await push(manifest('Same'), 0);
    expect(retry.status).toBe(200);
    expect((await retry.json()).revision).toBe(1);
  });

  it('rejects a manifest whose hash does not match', async () => {
    const res = await push(manifest('X'), 0, { hash: sha256('other') });
    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ error: 'hash_mismatch' });
  });

  it('needs every recording the manifest names to be uploaded first', async () => {
    const audio = new TextEncoder().encode('RIFF....WAVE');
    const ref = { sha256: sha256(audio), size: audio.length };
    const missing = await push(manifest('With audio', ref), 0);
    expect(missing.status).toBe(422);
    expect(await missing.json()).toEqual({ error: 'missing_blobs', missing: [ref.sha256] });
    await storeBlob(audio);
    expect((await push(manifest('With audio', ref), 0)).status).toBe(200);
  });

  it('deletes with compare-and-swap and leaves a tombstone in the feed', async () => {
    await push(manifest('Doomed'), 0);
    expect((await remove(0)).status).toBe(409);
    const res = await remove(1);
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ revision: 2, deleted: true, manifestHash: null });
    expect((await remove(1)).status).toBe(200);
    expect((await get()).status).toBe(410);
    expect((await feed()).changes).toMatchObject([{ syncId: ID, revision: 2, deleted: true }]);
  });

  it('lets a stale delete win unless the other version is longer', async () => {
    await push(manifest('Shared'), 0);
    await push(manifest('Renamed elsewhere'), 1, { changedAt: 1800000000000 });
    expect((await remove(1, 1000)).status).toBe(200);
    expect((await get()).status).toBe(410);

    await push(manifest('Back, longer'), 1, { durationMs: 2000 });
    expect((await get()).status).toBe(200);
    await push(manifest('Continued elsewhere'), 4, { durationMs: 3000 });
    const lost = await remove(4, 2000);
    expect(lost.status).toBe(409);
    expect(await lost.json()).toMatchObject({ current: { revision: 5, durationMs: 3000 } });
  });

  it('brings a deleted document back only with a longer recording', async () => {
    await push(manifest('Gone'), 0);
    await remove(1);
    const same = await push(manifest('Edited'), 1, { changedAt: 1900000000000 });
    expect(same.status).toBe(409);
    expect(await same.json()).toMatchObject({ current: { deleted: true } });
    expect((await push(manifest('Longer'), 1, { durationMs: 1001 })).status).toBe(200);
    expect((await (await get()).json()).title).toBe('Longer');
  });

  it('answers 404 for an unknown document or kind', async () => {
    expect((await get()).status).toBe(404);
    expect((await GET(request(`/documents/nope/${ID}`), ctx({ kind: 'nope', syncId: ID }))).status).toBe(404);
  });

  it('keeps a library item apart from a session with the same id, most recent edit winning', async () => {
    await push(manifest('Session'), 0);
    const template = (name: string, changedAt: number, baseRevision: number) => {
      const body = JSON.stringify({ schema: 1, syncId: ID, name, body: 'Bullets' });
      const headers = {
        'sync-base-revision': String(baseRevision),
        'sync-manifest-hash': sha256(body),
        'sync-duration-ms': '0',
        'sync-changed-at': String(changedAt),
      };
      return PUT(request(`/documents/template/${ID}`, { method: 'PUT', body, headers }), ctx({ kind: 'template', syncId: ID }));
    };
    expect((await template('Weekly', 1, 0)).status).toBe(200);
    expect((await template('Weekly (phone)', 3, 1)).status).toBe(200);
    // From the same stale base, an older edit loses and a newer one wins.
    expect((await template('Weekly (old)', 2, 1)).status).toBe(409);
    expect((await template('Weekly (Boox)', 4, 1)).status).toBe(200);
    const got = await GET(request(`/documents/template/${ID}`), ctx({ kind: 'template', syncId: ID }));
    expect((await got.json()).name).toBe('Weekly (Boox)');
    expect((await (await get()).json()).title).toBe('Session');
    expect((await feed()).changes.map((c) => [c.kind, c.revision])).toEqual([
      ['session', 1],
      ['template', 3],
    ]);
  });
});

describe('change feed', () => {
  it('lists writes in order and pages through them', async () => {
    const ids = ['a1', 'b2', 'c3'];
    for (const id of ids) await push(manifest(id), 0, { syncId: id });
    await push(manifest('a1 again'), 1, { syncId: 'a1' });

    const first = await feed(0, 2);
    expect(first.changes.map((c) => c.syncId)).toEqual(['b2', 'c3']);
    expect(first.more).toBe(true);

    const one = await feed(0, 1);
    expect(one.changes.map((c) => c.syncId)).toEqual(['b2']);
    expect(one.more).toBe(true);
    const rest = await feed(one.next, 10);
    expect(rest.changes.map((c) => [c.syncId, c.revision])).toEqual([
      ['c3', 1],
      ['a1', 2],
    ]);
    expect((await feed(rest.next)).changes).toEqual([]);
  });

  it('rejects a bad cursor or page size', async () => {
    expect((await changes(request('/changes?since=-1'))).status).toBe(400);
    expect((await changes(request('/changes?limit=0'))).status).toBe(400);
    expect((await changes(request('/changes?limit=501'))).status).toBe(400);
  });
});

describe('owners', () => {
  let proxy: AuthzDevServer;
  let proxyUrl: string;

  beforeAll(async () => {
    proxy = await createAuthzDevServer(
      { users: [{ username: 'alice@example.com', roles: ['convoscribe'] }] },
      { port: 0, host: '127.0.0.1', target: null, ui: false },
    );
    proxyUrl = (await proxy.listen()).url;
  });

  afterAll(async () => {
    await proxy.close();
  });

  it('keeps each owner apart, in both auth modes', async () => {
    await push(manifest('Default owner'), 0);

    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' });
    const asAlice = { headers: { authorization: 'Bearer sk_authz_abc_secret' } };
    expect((await GET(request(`/documents/session/${ID}`, asAlice), ctx({ kind: 'session', syncId: ID }))).status).toBe(404);
    expect(await (await changes(request('/changes', asAlice))).json()).toMatchObject({ changes: [] });

    const body = manifest('Alice');
    const headers = {
      ...asAlice.headers,
      'sync-base-revision': '0',
      'sync-manifest-hash': sha256(body),
      'sync-duration-ms': '1',
      'sync-changed-at': '1',
    };
    const res = await PUT(request(`/documents/session/${ID}`, { method: 'PUT', body, headers }), ctx({ kind: 'session', syncId: ID }));
    expect((await res.json()).revision).toBe(1);

    stubServerEnv();
    expect((await (await get()).json()).title).toBe('Default owner');
  });

  it('needs a key', async () => {
    const res = await changes(request('/changes', { headers: { authorization: '' } }));
    expect(res.status).toBe(401);
  });
});
