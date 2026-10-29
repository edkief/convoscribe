import { describe, expect, it } from 'vitest';
import { ctx, request, sha256, setupSyncServer } from '@/test/http';
import { POST as complete } from './uploads/[id]/complete/route';
import { GET as parts } from './uploads/[id]/route';
import { PUT as putPart } from './uploads/[id]/parts/[n]/route';
import { POST as start } from './uploads/route';
import { GET, HEAD } from './route';

setupSyncServer();

const bytes = new TextEncoder().encode('the quick brown fox jumps over the lazy dog');
const sha = sha256(bytes);

async function startUpload(hash = sha) {
  const res = await start(request(`/blobs/${hash}/uploads`, { method: 'POST' }), ctx({ sha: hash }));
  return { status: res.status, body: (await res.json()) as { id?: string; exists?: boolean } };
}

const sendPart = (id: string, n: number, body: Uint8Array, hash = sha) =>
  putPart(request(`/blobs/${hash}/uploads/${id}/parts/${n}`, { method: 'PUT', body: Buffer.from(body) }), ctx({ sha: hash, id, n: String(n) }));

const finish = (id: string, hash = sha) =>
  complete(request(`/blobs/${hash}/uploads/${id}/complete`, { method: 'POST' }), ctx({ sha: hash, id }));

async function upload() {
  const { body } = await startUpload();
  await sendPart(body.id!, 1, bytes.subarray(0, 20));
  await sendPart(body.id!, 2, bytes.subarray(20));
  return finish(body.id!);
}

describe('blob uploads', () => {
  it('stores parts and verifies the hash on completion', async () => {
    expect((await HEAD(request(`/blobs/${sha}`, { method: 'HEAD' }), ctx({ sha }))).status).toBe(404);
    const res = await upload();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ size: bytes.length });
    const head = await HEAD(request(`/blobs/${sha}`, { method: 'HEAD' }), ctx({ sha }));
    expect(head.status).toBe(200);
    expect(head.headers.get('content-length')).toBe(String(bytes.length));
    const got = await GET(request(`/blobs/${sha}`), ctx({ sha }));
    expect(new Uint8Array(await got.arrayBuffer())).toEqual(bytes);
  });

  it('says a stored blob exists instead of starting another upload', async () => {
    await upload();
    expect(await startUpload()).toEqual({ status: 200, body: { exists: true } });
  });

  it('lists received parts so an interrupted upload resumes', async () => {
    const { status, body } = await startUpload();
    expect(status).toBe(201);
    await sendPart(body.id!, 1, bytes.subarray(0, 20));
    const listed = await parts(request(`/blobs/${sha}/uploads/${body.id}`), ctx({ sha, id: body.id! }));
    expect(await listed.json()).toEqual({ parts: [{ n: 1, size: 20 }] });
    await sendPart(body.id!, 2, bytes.subarray(20));
    expect((await finish(body.id!)).status).toBe(200);
  });

  it('refuses to complete with a gap in the parts', async () => {
    const { body } = await startUpload();
    await sendPart(body.id!, 2, bytes.subarray(20));
    const res = await finish(body.id!);
    expect(res.status).toBe(409);
    expect(await res.json()).toMatchObject({ error: 'parts_missing', received: [2] });
  });

  it('discards an upload whose bytes do not match its hash', async () => {
    const { body } = await startUpload();
    await sendPart(body.id!, 1, new TextEncoder().encode('something else'));
    const res = await finish(body.id!);
    expect(res.status).toBe(400);
    expect(await res.json()).toMatchObject({ error: 'hash_mismatch' });
    expect((await HEAD(request(`/blobs/${sha}`, { method: 'HEAD' }), ctx({ sha }))).status).toBe(404);
  });

  it('serves a byte range', async () => {
    await upload();
    const res = await GET(request(`/blobs/${sha}`, { headers: { range: 'bytes=4-8' } }), ctx({ sha }));
    expect(res.status).toBe(206);
    expect(res.headers.get('content-range')).toBe(`bytes 4-8/${bytes.length}`);
    expect(await res.text()).toBe('quick');
  });

  it('keeps blobs per owner and needs a key', async () => {
    await upload();
    const anonymous = await HEAD(request(`/blobs/${sha}`, { method: 'HEAD', headers: { authorization: '' } }), ctx({ sha }));
    expect(anonymous.status).toBe(401);
    expect((await startUpload('0'.repeat(64))).status).toBe(201);
  });

  it('rejects a malformed hash', async () => {
    expect((await startUpload('not-a-hash')).status).toBe(400);
  });
});
