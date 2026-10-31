import { describe, expect, it } from 'vitest';
import { getDocumentMeta } from '@/lib/documents';
import { listTrash } from '@/lib/trash';
import { ctx, request, setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { DELETE as emptyTrash } from '../../trash/route';
import { POST as restore } from './restore/route';
import { DELETE } from './route';

setupSyncServer();

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

const del = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  DELETE(
    request(`/api/web/v1/sessions/${syncId}`, { method: 'DELETE', body: JSON.stringify(body), headers }),
    ctx({ syncId }),
  );
const undelete = (syncId: string, headers: Record<string, string> = ORIGIN) =>
  restore(request(`/api/web/v1/sessions/${syncId}/restore`, { method: 'POST', headers }), ctx({ syncId }));
const empty = (headers: Record<string, string> = ORIGIN) =>
  emptyTrash(request('/api/web/v1/trash', { method: 'DELETE', headers }));

describe('the web app’s trash routes (#81)', () => {
  it('deletes a session to the trash and restores it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    const res = await del('s1', { baseRevision: pushed.revision });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ syncId: 's1', revision: pushed.revision + 1 });
    expect(await listTrash(OWNER)).toHaveLength(1);

    const back = await undelete('s1');
    expect(back.status).toBe(200);
    expect(await getDocumentMeta(OWNER, 'session', 's1')).toMatchObject({ deleted: false, revision: pushed.revision + 2 });
    expect((await undelete('s1')).status).toBe(404);
  });

  it('refuses to delete from a page older than the session', async () => {
    const first = await pushManifest(OWNER, sessionManifest('s1'));
    await pushManifest(OWNER, sessionManifest('s1', { title: 'Edited' }), first.revision);
    const res = await del('s1', { baseRevision: first.revision });
    expect(res.status).toBe(409);
    expect((await res.json()).error).toBe('conflict');
  });

  it('refuses a bad body', async () => {
    await pushManifest(OWNER, sessionManifest('s1'));
    expect((await del('s1', { baseRevision: 'one' })).status).toBe(400);
    expect((await del('s1', null)).status).toBe(400);
  });

  it('empties the trash', async () => {
    for (const id of ['s1', 's2']) {
      const pushed = await pushManifest(OWNER, sessionManifest(id));
      await del(id, { baseRevision: pushed.revision });
    }
    const res = await empty();
    expect(await res.json()).toEqual({ purged: 2 });
    expect(await listTrash(OWNER)).toEqual([]);
    expect((await undelete('s1')).status).toBe(404);
  });

  it('never touches another owner’s sessions', async () => {
    const pushed = await pushManifest('bob', sessionManifest('b1'));
    expect((await del('b1', { baseRevision: pushed.revision })).status).toBe(404);
    expect(await getDocumentMeta('bob', 'session', 'b1')).toMatchObject({ deleted: false });
    await pushManifest('bob', sessionManifest('b2'));
    const { deleteDocument } = await import('@/lib/documents');
    await deleteDocument('bob', 'session', 'b2', 1, 65_000);
    expect((await undelete('b2')).status).toBe(404);
    expect(await (await empty()).json()).toEqual({ purged: 0 });
    expect(await listTrash('bob')).toHaveLength(1);
  });

  it('refuses requests from another site, and without sign-in', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    const elsewhere = { origin: 'https://evil.example', 'content-type': 'application/json' };
    expect((await del('s1', { baseRevision: pushed.revision }, elsewhere)).status).toBe(403);
    expect((await undelete('s1', {})).status).toBe(403);
    expect((await empty(elsewhere)).status).toBe(403);
    expect((await del('s1', { baseRevision: pushed.revision }, { ...ORIGIN, authorization: '' })).status).toBe(401);
    expect(await getDocumentMeta(OWNER, 'session', 's1')).toMatchObject({ deleted: false });
  });
});
