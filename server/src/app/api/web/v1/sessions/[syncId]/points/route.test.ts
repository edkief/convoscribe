import { describe, expect, it } from 'vitest';
import { TALKING_POINTS_SYSTEM } from '@/lib/assist-prompt';
import { stubServerEnv } from '@/test/env';
import { ctx, request, setupSyncServer } from '@/test/http';
import { answer, events, fakeLlm, manifestOf } from '@/test/llm-upstream';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { POST } from './route';

setupSyncServer();
const llm = fakeLlm(answer('[{"point": 2, "at": "00:04"}]'));

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

const check = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request(`/api/web/v1/sessions/${syncId}/points`, { method: 'POST', body: JSON.stringify(body), headers }), ctx({ syncId }));

const point = (id: string, text: string, over: Record<string, unknown> = {}) => ({ id, text, covered: false, coveredAtMs: null, byUser: false, createdAt: 1, ...over });

const POINTS = [
  point('a', 'Greetings'),
  point('b', 'How Bob is'),
  point('c', 'Budget', { covered: true, coveredAtMs: 1000 }),
  point('d', 'Hiring', { byUser: true }),
  point('e', 'Office', { covered: true, byUser: true, coveredAtMs: 2000, pointFuture: 'kept' }),
];

describe('POST /api/web/v1/sessions/<syncId>/points (#119)', () => {
  it('ticks the points the model finds, with where, and leaves the others alone', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision + 1, ticked: 1 }]);
    expect(llm.calls).toEqual([
      [
        { role: 'system', content: TALKING_POINTS_SYSTEM },
        { role: 'user', content: 'Points not covered yet:\n1. Greetings\n2. How Bob is\n\nLatest transcript:\n\n[00:00] Alice: Hello there.\n[00:04] Bob: Hi, how are you?' },
      ],
    ]);
    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest.talkingPoints).toEqual([POINTS[0], { ...POINTS[1], covered: true, coveredAtMs: 4000 }, POINTS[2], POINTS[3], POINTS[4]]);
  });

  it('ticks a point at the last line of the part when the time given is outside it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: [point('a', 'Greetings')] }));
    llm.reply = answer('[{"point": 1, "at": "45:00"}]');
    await events(await check('s1', { baseRevision: pushed.revision }));
    expect((await manifestOf(OWNER, 's1')).manifest.talkingPoints[0]).toMatchObject({ covered: true, coveredAtMs: 4000 });
  });

  it('checks a long transcript in parts, sending only the points still open', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: process.env.LLM_UPSTREAM_URL!, LLM_MODEL: 'm', LLM_MAX_TRANSCRIPT_CHARS: '1000' });
    const lines = Array.from({ length: 80 }, (_, i) => ({ startMs: i * 60_000, endMs: i * 60_000 + 50_000, text: `we talked about subject number ${i} at length.`, speakerIndex: 0, tokens: [] }));
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { lines, talkingPoints: [point('a', 'First'), point('b', 'Second')] }));
    llm.reply = (messages, res) => answer(llm.calls.length === 1 ? '[{"point": 1, "at": "00:01:00"}]' : '[]')(messages, res);
    const out = await events(await check('s1', { baseRevision: pushed.revision }));
    const steps = out.filter((e) => 'step' in e);
    expect(steps.length).toBeGreaterThan(1);
    expect(steps[0]).toEqual({ step: { part: 1, parts: steps.length } });
    expect(llm.calls.length).toBe(steps.length);
    expect(llm.calls[1][1].content).toMatch(/^Points not covered yet:\n1\. Second\n\n/);
    expect(out.at(-1)).toEqual({ done: true, revision: pushed.revision + 1, ticked: 1 });
    expect((await manifestOf(OWNER, 's1')).manifest.talkingPoints[0]).toMatchObject({ covered: true, coveredAtMs: 60_000 });
  });

  it('saves no revision when nothing is covered', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    llm.reply = answer('None of them.');
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision, ticked: 0 }]);
  });

  it('saves nothing for an unreadable answer, an upstream failure, or a session changed meanwhile', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    llm.reply = answer('Points 1 and 2.');
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'unreadable_reply' }]);
    llm.reply = (_m, res) => {
      res.writeHead(500);
      res.end('down');
    };
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'upstream_error' }]);
    llm.reply = async (messages, res) => {
      await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS, title: 'Renamed' }), pushed.revision);
      answer('[1]')(messages, res);
    };
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'conflict' }]);
    expect((await manifestOf(OWNER, 's1')).manifest.talkingPoints).toEqual(POINTS);
  });

  it('refuses what it cannot start, as a JSON error', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    await pushManifest(OWNER, sessionManifest('done', { talkingPoints: [POINTS[2], POINTS[3]] }));
    await pushManifest(OWNER, sessionManifest('quiet', { lines: [], talkingPoints: POINTS }));
    await pushManifest(OWNER, sessionManifest('newer', { schema: 99, talkingPoints: POINTS }));
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await check('s1', { baseRevision: pushed.revision + 1 }))).toEqual([409, 'conflict']);
    expect(await status(await check('done', { baseRevision: 1 }))).toEqual([422, 'no_open_points']);
    expect(await status(await check('quiet', { baseRevision: 1 }))).toEqual([422, 'empty_transcript']);
    expect(await status(await check('newer', { baseRevision: 1 }))).toEqual([422, 'newer_schema']);
    expect(await status(await check('s1', { baseRevision: 'x' }))).toEqual([400, 'invalid_body']);
    stubServerEnv();
    expect(await status(await check('s1', { baseRevision: pushed.revision }))).toEqual([404, 'not_offered']);
    expect(llm.calls).toEqual([]);
  });
});
