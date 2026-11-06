import { describe, expect, it } from 'vitest';
import { MARK_NOTE_SYSTEM } from '@/lib/assist-prompt';
import { getDocumentMeta } from '@/lib/documents';
import { stubServerEnv } from '@/test/env';
import { ctx, request, setupSyncServer } from '@/test/http';
import { answer, events, fakeLlm, manifestOf, sse } from '@/test/llm-upstream';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { POST } from './route';

setupSyncServer();
const llm = fakeLlm(answer('  Bob was asked how he is.  '));

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

const write = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request(`/api/web/v1/sessions/${syncId}/mark-notes`, { method: 'POST', body: JSON.stringify(body), headers }), ctx({ syncId }));

const MARKS = [
  { atMs: 1000, note: 'hello', createdAt: 1 },
  { atMs: 5000, note: null, createdAt: 2, expandedNote: 'Old note.', expandedAt: 5, expandedStale: true, markFuture: 'kept' },
  { atMs: 6000, note: 'current', createdAt: 3, expandedNote: 'Current note.', expandedAt: 5, expandedStale: false },
];

describe('POST /api/web/v1/sessions/<syncId>/mark-notes (#119)', () => {
  it('writes the missing and stale notes, and saves them as one revision', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS }));
    const out = await events(await write('s1', { baseRevision: pushed.revision }));
    expect(out).toEqual([
      { progress: { done: 0, total: 2 } },
      { progress: { done: 1, total: 2 } },
      { progress: { done: 2, total: 2 } },
      { done: true, revision: pushed.revision + 1, written: 2 },
    ]);
    expect(llm.calls).toHaveLength(2);
    expect(llm.calls[0][0]).toEqual({ role: 'system', content: MARK_NOTE_SYSTEM });
    expect(llm.calls[0][1].content).toBe('Marked at [00:01]. The user\'s note: "hello"\n\nTranscript around it:\n\n[00:00] Alice: Hello there.\n[00:04] Bob: Hi, how are you?');
    expect(llm.calls[1][1].content).toMatch(/^Marked at \[00:05\]\. The user left no note/);

    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest.marks[0]).toEqual({ ...MARKS[0], expandedNote: 'Bob was asked how he is.', expandedAt: expect.any(Number), expandedStale: false });
    expect(manifest.marks[1]).toMatchObject({ expandedNote: 'Bob was asked how he is.', expandedStale: false, markFuture: 'kept' });
    expect(manifest.marks[1].expandedAt).toBeGreaterThan(5);
    expect(manifest.marks[2]).toEqual(MARKS[2]);
  });

  it('skips a mark whose note came back blank', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS }));
    llm.reply = (messages, res) => answer(llm.calls.length === 1 ? '  ' : 'Written.')(messages, res);
    expect((await events(await write('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ done: true, revision: pushed.revision + 1, written: 1 });
    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest.marks[0].expandedNote).toBeUndefined();
    expect(manifest.marks[1].expandedNote).toBe('Written.');
  });

  it('saves nothing when every note came back blank, the model failed, or the session changed meanwhile', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS }));
    llm.reply = answer(' ');
    expect((await events(await write('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ error: 'empty_reply' });
    llm.reply = (_m, res) => {
      res.writeHead(401);
      res.end();
    };
    expect((await events(await write('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ error: 'upstream_rejected' });
    let changed = false;
    llm.reply = async (messages, res) => {
      if (!changed && (changed = true)) await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS, title: 'Renamed' }), pushed.revision);
      answer('Note.')(messages, res);
    };
    expect((await events(await write('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ error: 'conflict' });
    expect((await manifestOf(OWNER, 's1')).manifest.marks).toEqual(MARKS);
  });

  it('saves nothing when the page stops it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS }));
    let finish!: () => void;
    llm.reply = (messages, res) => {
      if (llm.calls.length === 1) return answer('First note.')(messages, res);
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(sse('Second').replace('data: [DONE]\n\n', ''));
      finish = () => res.end(sse(' note.'));
    };
    const res = await write('s1', { baseRevision: pushed.revision });
    const reader = res.body!.getReader();
    while (!finish) {
      await reader.read();
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    await reader.cancel();
    finish();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('refuses what it cannot start, as a JSON error', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { marks: MARKS }));
    await pushManifest(OWNER, sessionManifest('written', { marks: [MARKS[2]] }));
    await pushManifest(OWNER, sessionManifest('quiet', { lines: [], marks: MARKS }));
    await pushManifest(OWNER, sessionManifest('newer', { schema: 99, marks: MARKS }));
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await write('s1', { baseRevision: pushed.revision + 1 }))).toEqual([409, 'conflict']);
    expect(await status(await write('written', { baseRevision: 1 }))).toEqual([422, 'no_marks']);
    expect(await status(await write('quiet', { baseRevision: 1 }))).toEqual([422, 'empty_transcript']);
    expect(await status(await write('newer', { baseRevision: 1 }))).toEqual([422, 'newer_schema']);
    stubServerEnv();
    expect(await status(await write('s1', { baseRevision: pushed.revision }))).toEqual([404, 'not_offered']);
    expect(llm.calls).toEqual([]);
  });
});
