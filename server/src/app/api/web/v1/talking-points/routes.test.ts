import { describe, expect, it } from 'vitest';
import { getDocumentMeta, listChanges, readManifest } from '@/lib/documents';
import { ctx, request, setupSyncServer } from '@/test/http';
import { listManifest, pushManifest } from '@/test/sessions';
import { DELETE, PUT } from './[syncId]/route';
import { GET, POST } from './route';

setupSyncServer();

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

const list = () => GET(request('/api/web/v1/talking-points'));
const create = (body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request('/api/web/v1/talking-points', { method: 'POST', body: JSON.stringify(body), headers }));
const save = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  PUT(request(`/api/web/v1/talking-points/${syncId}`, { method: 'PUT', body: JSON.stringify(body), headers }), ctx({ syncId }));
const del = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  DELETE(request(`/api/web/v1/talking-points/${syncId}`, { method: 'DELETE', body: JSON.stringify(body), headers }), ctx({ syncId }));

/** The manifest devices download for a list. */
async function stored(syncId: string, owner = OWNER) {
  const meta = await getDocumentMeta(owner, 'talking-points', syncId);
  return JSON.parse(Buffer.from(await readManifest(owner, 'talking-points', syncId, meta!.manifestHash!)).toString('utf8'));
}

describe('the web app’s talking-point routes (#104)', () => {
  it('creates a list as a device would, and lists it', async () => {
    const res = await create({ name: '  Weekly  1:1 ', points: ['Hiring', ' ', ' Budget\nfor Q3 '] });
    expect(res.status).toBe(201);
    const { syncId, revision } = await res.json();
    expect(revision).toBe(1);
    expect(await stored(syncId)).toEqual({ schema: 1, syncId, name: 'Weekly 1:1', points: ['Hiring', 'Budget for Q3'], createdAt: expect.any(Number) });
    expect((await listChanges(OWNER, 0, 10)).changes).toMatchObject([{ kind: 'talking-points', syncId }]);
    expect((await (await list()).json()).lists).toMatchObject([{ syncId, revision: 1, name: 'Weekly 1:1', points: ['Hiring', 'Budget for Q3'] }]);
  });

  it('saves a device’s list, keeping fields it does not know', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A', 'B'], { colour: 'teal' }), 0, 'talking-points');
    const res = await save('l1', { baseRevision: 1, name: 'Review', points: ['B', 'A', 'C'] });
    expect(await res.json()).toEqual({ syncId: 'l1', revision: 2 });
    expect(await stored('l1')).toEqual({ ...listManifest('l1', 'Review', ['B', 'A', 'C']), colour: 'teal' });
    // The same again changes nothing.
    expect(await (await save('l1', { baseRevision: 2, name: 'Review', points: ['B', 'A', 'C'] })).json()).toEqual({ syncId: 'l1', revision: 2 });
  });

  it('refuses a save or delete from a stale page', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A']), 0, 'talking-points');
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A', 'From the phone']), 1, 'talking-points');
    const res = await save('l1', { baseRevision: 1, name: 'Review', points: ['A', 'From the web'] });
    expect(res.status).toBe(409);
    expect((await res.json()).error).toBe('conflict');
    expect((await del('l1', { baseRevision: 1 })).status).toBe(409);
    expect((await stored('l1')).points).toEqual(['A', 'From the phone']);
  });

  it('deletes a list for every device', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A']), 0, 'talking-points');
    expect(await (await del('l1', { baseRevision: 1 })).json()).toEqual({ syncId: 'l1', revision: 2 });
    expect(await getDocumentMeta(OWNER, 'talking-points', 'l1')).toMatchObject({ deleted: true });
    expect((await (await list()).json()).lists).toEqual([]);
    expect((await del('l1', { baseRevision: 2 })).status).toBe(404);
    expect((await save('l1', { baseRevision: 2, name: 'Back', points: [] })).status).toBe(404);
  });

  it('refuses one from a newer app', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A'], { schema: 2 }), 0, 'talking-points');
    expect((await save('l1', { baseRevision: 1, name: 'Review', points: [] })).status).toBe(422);
  });

  it('refuses a bad body', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A']), 0, 'talking-points');
    expect((await create({ name: '   ', points: [] })).status).toBe(400);
    expect((await create({ name: 'X', points: 'A' })).status).toBe(400);
    expect((await create({ name: 'X', points: [1] })).status).toBe(400);
    expect((await create({ name: 'X', points: Array.from({ length: 201 }, (_, i) => `P${i}`) })).status).toBe(400);
    expect((await save('l1', { name: 'X', points: [] })).status).toBe(400);
    expect((await del('l1', {})).status).toBe(400);
    expect((await save('not valid!', { baseRevision: 1, name: 'X', points: [] })).status).toBe(400);
  });

  it('shows and touches only your own lists', async () => {
    await pushManifest('bob', listManifest('b1', 'Bob’s', ['Secret']), 0, 'talking-points');
    expect((await (await list()).json()).lists).toEqual([]);
    expect((await save('b1', { baseRevision: 1, name: 'Mine', points: [] })).status).toBe(404);
    expect((await del('b1', { baseRevision: 1 })).status).toBe(404);
    expect(await getDocumentMeta('bob', 'talking-points', 'b1')).toMatchObject({ revision: 1, deleted: false });
  });

  it('refuses requests from another site, and without sign-in', async () => {
    await pushManifest(OWNER, listManifest('l1', 'Review', ['A']), 0, 'talking-points');
    const elsewhere = { origin: 'https://evil.example', 'content-type': 'application/json' };
    expect((await create({ name: 'X', points: [] }, elsewhere)).status).toBe(403);
    expect((await save('l1', { baseRevision: 1, name: 'X', points: [] }, elsewhere)).status).toBe(403);
    expect((await del('l1', { baseRevision: 1 }, {})).status).toBe(403);
    const anonymous = new Request('http://server/api/web/v1/talking-points');
    expect((await GET(anonymous)).status).toBe(401);
  });
});
