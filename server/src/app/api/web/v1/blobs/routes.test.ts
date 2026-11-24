import { describe, expect, it } from 'vitest';
import { setServices } from '@/lib/access';
import { blobSize } from '@/lib/blobs';
import { newSessionValue, SESSION_COOKIE } from '@/lib/auth';
import { noteActivity, setQuota } from '@/lib/owners';
import { ctx, request, sha256, setupSyncServer } from '@/test/http';
import { POST as complete } from './[sha]/uploads/[id]/complete/route';
import { PUT as putPart } from './[sha]/uploads/[id]/parts/[n]/route';
import { GET as parts } from './[sha]/uploads/[id]/route';
import { POST as start } from './[sha]/uploads/route';

setupSyncServer();

const OWNER = 'default';
const bytes = new TextEncoder().encode('a recording made in the browser, in two parts');
const sha = sha256(bytes);

/** A signed-in browser on this server's page: the cookie and the page's Origin, no Bearer key. */
const BROWSER = () => ({ authorization: '', cookie: `${SESSION_COOKIE}=${newSessionValue()}`, origin: 'http://server' });

const startUpload = (headers: Record<string, string> = BROWSER()) =>
  start(request(`/api/web/v1/blobs/${sha}/uploads`, { method: 'POST', headers }), ctx({ sha }));

const sendPart = (id: string, n: number, body: Uint8Array, headers: Record<string, string> = BROWSER()) =>
  putPart(
    request(`/api/web/v1/blobs/${sha}/uploads/${id}/parts/${n}`, { method: 'PUT', body: Buffer.from(body), headers }),
    ctx({ sha, id, n: String(n) }),
  );

const listParts = (id: string, headers: Record<string, string> = BROWSER()) =>
  parts(request(`/api/web/v1/blobs/${sha}/uploads/${id}`, { headers }), ctx({ sha, id }));

const finish = (id: string, headers: Record<string, string> = BROWSER()) =>
  complete(request(`/api/web/v1/blobs/${sha}/uploads/${id}/complete`, { method: 'POST', headers }), ctx({ sha, id }));

describe('web blob uploads (#163)', () => {
  it('uploads in parts, resumes from the parts received, and verifies the hash', async () => {
    const started = await startUpload();
    expect(started.status).toBe(201);
    const { id } = (await started.json()) as { id: string };
    expect((await sendPart(id, 1, bytes.subarray(0, 20))).status).toBe(204);
    expect(await (await listParts(id)).json()).toEqual({ parts: [{ n: 1, size: 20 }] });
    // A part sent again replaces it.
    expect((await sendPart(id, 1, bytes.subarray(0, 20))).status).toBe(204);
    expect((await sendPart(id, 2, bytes.subarray(20))).status).toBe(204);
    const done = await finish(id);
    expect(done.status).toBe(200);
    expect(await done.json()).toEqual({ size: bytes.length });
    expect(await blobSize(OWNER, sha)).toBe(bytes.length);
  });

  it('says a stored recording exists instead of starting another upload', async () => {
    const { id } = (await (await startUpload()).json()) as { id: string };
    await sendPart(id, 1, bytes);
    await finish(id);
    const again = await startUpload();
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ exists: true });
  });

  it('answers what the sync routes answer for a gap, a bad hash and an unknown upload', async () => {
    const { id } = (await (await startUpload()).json()) as { id: string };
    await sendPart(id, 2, bytes);
    const gap = await finish(id);
    expect(gap.status).toBe(409);
    expect(await gap.json()).toEqual({ error: 'parts_missing', received: [2] });
    expect((await listParts('00000000-0000-0000-0000-000000000000')).status).toBe(404);
    const bad = await start(request('/api/web/v1/blobs/nothex/uploads', { method: 'POST', headers: BROWSER() }), ctx({ sha: 'nothex' }));
    expect(bad.status).toBe(400);
  });

  it('refuses another site, a missing Origin and a browser not signed in', async () => {
    const cross = await startUpload({ ...BROWSER(), origin: 'https://evil.example' });
    expect(cross.status).toBe(403);
    expect(await cross.json()).toEqual({ error: 'cross_site' });
    const { origin: _, ...noOrigin } = BROWSER();
    expect((await startUpload(noOrigin)).status).toBe(403);
    expect((await startUpload({ authorization: '', origin: 'http://server' })).status).toBe(401);
  });

  it('needs the sync service', async () => {
    await noteActivity(OWNER);
    await setServices(OWNER, ['llm']);
    const res = await startUpload();
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: 'service_not_allowed', service: 'sync' });
  });

  it('keeps to the quota, as the sync routes do', async () => {
    await noteActivity(OWNER);
    await setQuota(OWNER, 30);
    const { id } = (await (await startUpload()).json()) as { id: string };
    const over = await sendPart(id, 1, bytes);
    expect(over.status).toBe(507);
    expect(await over.json()).toEqual({ error: 'quota_exceeded' });
    await setQuota(OWNER, 0);
    expect((await startUpload()).status).toBe(507);
  });
});
