import { describe, expect, it } from 'vitest';
import { TITLE_SYSTEM } from '@/lib/assist-prompt';
import { getDocumentMeta } from '@/lib/documents';
import { stubServerEnv } from '@/test/env';
import { ctx, request, setupSyncServer } from '@/test/http';
import { answer, events, fakeLlm, manifestOf, sse } from '@/test/llm-upstream';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { POST } from './route';

setupSyncServer();
const llm = fakeLlm(answer('Title: "Planning the Q3 budget."'));

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

const suggest = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request(`/api/web/v1/sessions/${syncId}/title`, { method: 'POST', body: JSON.stringify(body), headers }), ctx({ syncId }));

const SUMMARY = { kind: 'SUMMARY', role: 'ASSISTANT', content: 'We set the Q3 budget.', createdAt: 1 };

describe('POST /api/web/v1/sessions/<syncId>/title (#119)', () => {
  it('names the session from its summary and keeps the title it replaced', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { title: 'Weekly sync', titleSource: 'user', language: 'fr', messages: [SUMMARY], future: 1 }));
    const res = await suggest('s1', { baseRevision: pushed.revision });
    expect(res.headers.get('content-type')).toContain('application/x-ndjson');
    expect(await events(res)).toEqual([{ done: true, revision: pushed.revision + 1, title: 'Planning the Q3 budget' }]);
    expect(llm.calls).toEqual([
      [
        { role: 'system', content: TITLE_SYSTEM + ' Write it in French.' },
        { role: 'user', content: 'Summary:\n\nWe set the Q3 budget.' },
      ],
    ]);
    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest).toMatchObject({ title: 'Planning the Q3 budget', titleSource: 'auto', previousTitle: 'Weekly sync', future: 1 });
  });

  it('uses the transcript without a summary', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    await events(await suggest('s1', { baseRevision: pushed.revision }));
    expect(llm.calls[0][1].content).toBe('Transcript:\n\n[00:00] Alice: Hello there.\n[00:04] Bob: Hi, how are you?');
  });

  it('keeps the title from before an earlier automatic one, to put back', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { title: 'Budget', titleSource: 'auto', previousTitle: 'Recording 2025-10-09', messages: [SUMMARY] }));
    await events(await suggest('s1', { baseRevision: pushed.revision }));
    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest).toMatchObject({ title: 'Planning the Q3 budget', previousTitle: 'Recording 2025-10-09' });
  });

  it('saves no revision for the same title', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { title: 'Planning the Q3 budget' }));
    expect(await events(await suggest('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision, title: 'Planning the Q3 budget' }]);
  });

  it('saves nothing for an answer with no title, or when the session changed meanwhile', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    llm.reply = answer('"..."');
    expect(await events(await suggest('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'empty_reply' }]);
    llm.reply = async (messages, res) => {
      await pushManifest(OWNER, sessionManifest('s1', { title: 'Renamed on a device' }), pushed.revision);
      answer('A title')(messages, res);
    };
    expect(await events(await suggest('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'conflict' }]);
    expect((await manifestOf(OWNER, 's1')).manifest.title).toBe('Renamed on a device');
  });

  it('saves nothing when the page stops it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    let finish!: () => void;
    llm.reply = (_m, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(sse('Budget').replace('data: [DONE]\n\n', ''));
      finish = () => res.end(sse(' talk'));
    };
    const res = await suggest('s1', { baseRevision: pushed.revision });
    await new Promise((resolve) => setTimeout(resolve, 50));
    await res.body!.cancel();
    finish();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('refuses what it cannot start, as a JSON error', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    await pushManifest(OWNER, sessionManifest('newer', { schema: 99 }));
    await pushManifest(OWNER, sessionManifest('quiet', { lines: [] }));
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await suggest('s1', { baseRevision: pushed.revision + 1 }))).toEqual([409, 'conflict']);
    expect(await status(await suggest('newer', { baseRevision: 1 }))).toEqual([422, 'newer_schema']);
    expect(await status(await suggest('quiet', { baseRevision: 1 }))).toEqual([422, 'empty_transcript']);
    expect(await status(await suggest('nope', { baseRevision: 1 }))).toEqual([404, 'not_found']);
    expect(await status(await suggest('s1', { baseRevision: pushed.revision }, { 'content-type': 'application/json', origin: 'https://evil.example' }))).toEqual([403, 'cross_site']);
    stubServerEnv();
    expect(await status(await suggest('s1', { baseRevision: pushed.revision }))).toEqual([404, 'not_offered']);
    expect(llm.calls).toEqual([]);
  });
});
