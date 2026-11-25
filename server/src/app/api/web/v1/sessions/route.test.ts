import { describe, expect, it } from 'vitest';
import { deleteDocument, getDocumentMeta, listChanges } from '@/lib/documents';
import { newSessionValue, SESSION_COOKIE } from '@/lib/auth';
import { listSessions } from '@/lib/session-index';
import { searchSessions } from '@/lib/session-search';
import { request, setupSyncServer } from '@/test/http';
import { manifestOf } from '@/test/llm-upstream';
import { storeBlob } from '@/test/sessions';
import { POST } from './route';

setupSyncServer();

const OWNER = 'default';
const SYNC_ID = '0f6c1c64-6a3e-4a63-9a39-6a4b0e0d2b11';
const BROWSER = () => ({ authorization: '', cookie: `${SESSION_COOKIE}=${newSessionValue()}`, origin: 'http://server', 'content-type': 'application/json' });

const create = (body: unknown, headers: Record<string, string> = BROWSER()) =>
  POST(request('/api/web/v1/sessions', { method: 'POST', body: typeof body === 'string' ? body : JSON.stringify(body), headers }));

async function recording() {
  return storeBlob(OWNER, 'RIFF a recording made in the browser');
}

const body = (audioSha: string, over: Record<string, unknown> = {}) => ({
  syncId: SYNC_ID,
  audioSha,
  durationMs: 9000,
  createdAt: 1_760_000_000_000,
  language: 'en',
  lines: [
    { startMs: 0, endMs: 4000, text: 'Hello there, budget planning.' },
    { startMs: 4000, endMs: 9000, text: 'Hi, how are you?' },
  ],
  zone: 'Europe/Paris',
  asrModel: 'asr-en-kroko-2025-08-06',
  ...over,
});

describe('POST /api/web/v1/sessions (#163)', () => {
  it('creates a session that is listed, searchable and in the feed for devices', async () => {
    const audio = await recording();
    const res = await create(body(audio.sha256));
    expect(res.status).toBe(201);
    expect(await res.json()).toEqual({ syncId: SYNC_ID, revision: 1 });

    const { manifest } = await manifestOf(OWNER, SYNC_ID);
    expect(manifest).toMatchObject({
      schema: 1,
      syncId: SYNC_ID,
      title: 'Recording 2025-10-09 10:53',
      titleSource: 'default',
      status: 'DIARIZATION_PENDING',
      asrEngine: 'browser',
      asrModel: 'asr-en-kroko-2025-08-06',
      audio,
    });
    expect((await listSessions(OWNER)).sessions).toMatchObject([
      { syncId: SYNC_ID, title: 'Recording 2025-10-09 10:53', durationMs: 9000, status: 'DIARIZATION_PENDING' },
    ]);
    expect((await searchSessions(OWNER, 'budget')).sessions.map((s) => s.syncId)).toEqual([SYNC_ID]);
    expect((await listChanges(OWNER, 0, 10)).changes).toMatchObject([{ kind: 'session', syncId: SYNC_ID, revision: 1, durationMs: 9000 }]);
  });

  it('answers a request sent again with the same revision, and no new one', async () => {
    const audio = await recording();
    await create(body(audio.sha256, { title: 'Standup' }));
    const again = await create(body(audio.sha256, { title: 'Standup' }));
    expect(again.status).toBe(200);
    expect(await again.json()).toEqual({ syncId: SYNC_ID, revision: 1 });
    expect((await listChanges(OWNER, 0, 10)).changes).toHaveLength(1);
  });

  it('refuses another session under the same syncId, and a deleted one', async () => {
    const audio = await recording();
    await create(body(audio.sha256));
    const other = await create(body(audio.sha256, { title: 'Something else' }));
    expect(other.status).toBe(409);
    expect(await other.json()).toMatchObject({ error: 'conflict', current: { revision: 1 } });
    await deleteDocument(OWNER, 'session', SYNC_ID, 1);
    expect((await create(body(audio.sha256))).status).toBe(409);
    expect(await getDocumentMeta(OWNER, 'session', SYNC_ID)).toMatchObject({ deleted: true, revision: 2 });
  });

  it('waits for the recording', async () => {
    const missing = 'b'.repeat(64);
    const res = await create(body(missing));
    expect(res.status).toBe(422);
    expect(await res.json()).toEqual({ error: 'missing_blobs', missing: [missing] });
    expect(await getDocumentMeta(OWNER, 'session', SYNC_ID)).toBeNull();
  });

  it('refuses a malformed body, another site and a browser not signed in', async () => {
    const audio = await recording();
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await create('{not json'))).toEqual([400, 'invalid_body']);
    expect(await status(await create(body(audio.sha256, { lines: [{ startMs: 1 }] })))).toEqual([400, 'invalid_body']);
    expect(await status(await create(body(audio.sha256), { ...BROWSER(), origin: 'https://evil.example' }))).toEqual([403, 'cross_site']);
    expect(await status(await create(body(audio.sha256), { authorization: '', origin: 'http://server' }))).toEqual([401, 'unauthorized']);
    expect(await getDocumentMeta(OWNER, 'session', SYNC_ID)).toBeNull();
  });
});
