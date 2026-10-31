import { describe, expect, it } from 'vitest';
import { sessionIndex } from '@/db/schema';
import { db } from '@/lib/db';
import { deleteDocument } from '@/lib/documents';
import { ctx, request, setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { GET as transcript } from '../transcript/[format]/route';
import { GET } from './route';

setupSyncServer();

const OWNER = 'default';
const audio = (syncId: string, query = '', headers: Record<string, string> = {}) =>
  GET(request(`/api/web/v1/sessions/${syncId}/audio${query}`, { headers }), ctx({ syncId }));

async function sessionWithAudio(owner = OWNER, syncId = 's1') {
  const original = await storeBlob(owner, 'RIFF-original-recording-bytes');
  const clean = await storeBlob(owner, 'RIFF-cleaned');
  await pushManifest(owner, sessionManifest(syncId, { title: 'Stand-up', audio: original, cleanAudio: clean }));
  return { original, clean };
}

describe('GET /api/web/v1/sessions/<id>/audio (#80)', () => {
  it('streams the recording, whole or by range', async () => {
    await sessionWithAudio();
    const whole = await audio('s1');
    expect(whole.status).toBe(200);
    expect(whole.headers.get('content-type')).toBe('audio/wav');
    expect(whole.headers.get('accept-ranges')).toBe('bytes');
    expect(whole.headers.get('content-disposition')).toBeNull();
    expect(await whole.text()).toBe('RIFF-original-recording-bytes');

    const part = await audio('s1', '', { range: 'bytes=5-12' });
    expect(part.status).toBe(206);
    expect(part.headers.get('content-range')).toBe('bytes 5-12/29');
    expect(await part.text()).toBe('original');
  });

  it('gives the cleaned recording, and a download named after the session', async () => {
    await sessionWithAudio();
    const res = await audio('s1', '?clean=1&download=1');
    expect(await res.text()).toBe('RIFF-cleaned');
    expect(res.headers.get('content-disposition')).toMatch(/filename="Stand-up \(cleaned\)\.wav"/);
  });

  it('reads the manifest when the index row is missing', async () => {
    await sessionWithAudio();
    await db().delete(sessionIndex);
    expect(await (await audio('s1')).text()).toBe('RIFF-original-recording-bytes');
  });

  it("is 404 for another owner's session, a deleted one, or one without that recording", async () => {
    await sessionWithAudio('bob', 'bobs');
    expect((await audio('bobs')).status).toBe(404);
    await pushManifest(OWNER, sessionManifest('silent'));
    expect((await audio('silent')).status).toBe(404);
    await sessionWithAudio(OWNER, 'gone');
    await deleteDocument(OWNER, 'session', 'gone', 1);
    expect((await audio('gone')).status).toBe(404);
  });

  it('needs a signed-in browser', async () => {
    await sessionWithAudio();
    const res = await GET(request('/api/web/v1/sessions/s1/audio', { headers: { authorization: '' } }), ctx({ syncId: 's1' }));
    expect(res.status).toBe(401);
  });
});

describe('GET /api/web/v1/sessions/<id>/transcript/<format> (#80)', () => {
  const get = (syncId: string, format: string) =>
    transcript(request(`/api/web/v1/sessions/${syncId}/transcript/${format}`), ctx({ syncId, format }));

  it('downloads Markdown, SRT and VTT', async () => {
    await pushManifest(OWNER, sessionManifest('s1', { title: 'Stand-up' }));
    const md = await get('s1', 'md');
    expect(md.headers.get('content-type')).toMatch(/^text\/markdown/);
    expect(md.headers.get('content-disposition')).toMatch(/filename="Stand-up\.md"/);
    expect(await md.text()).toMatch(/^# Stand-up\n/);
    expect(await (await get('s1', 'srt')).text()).toMatch(/^1\n00:00:00,000 --> 00:00:04,000\nAlice: Hello there\./);
    expect(await (await get('s1', 'vtt')).text()).toMatch(/^WEBVTT\n/);
  });

  it("is 404 for an unknown format or another owner's session", async () => {
    await pushManifest(OWNER, sessionManifest('s1'));
    await pushManifest('bob', sessionManifest('bobs'));
    for (const format of ['pdf', 'constructor', '__proto__']) expect((await get('s1', format)).status).toBe(404);
    expect((await get('bobs', 'md')).status).toBe(404);
  });
});
