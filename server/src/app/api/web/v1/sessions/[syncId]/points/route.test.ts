import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { TALKING_POINTS_SYSTEM } from '@/lib/assist-prompt';
import { setServices } from '@/lib/access';
import { noteActivity } from '@/lib/owners';
import { stubServerEnv } from '@/test/env';
import { ctx, request, setupSyncServer } from '@/test/http';
import { answer, events, fakeLlm, manifestOf } from '@/test/llm-upstream';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { NONE_KEY, NONE_TEXT } from '@/lib/talking-point-decision';
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

  it('asks the language model for low reasoning the server’s way (#136)', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: process.env.LLM_UPSTREAM_URL!, LLM_MODEL: 'm', LLM_LOW_REASONING: '{"chat_template_kwargs":{"reasoning_strength":"none"}}' });
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    await events(await check('s1', { baseRevision: pushed.revision }));
    expect(llm.bodies[0].chat_template_kwargs).toEqual({ reasoning_strength: 'none' });
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

type Batch = { states: string[]; questions: Record<string, { type: string; criteria: Record<string, string> }>; model?: string };

/**
 * A fake laya decision service: for each line, the point whose text a word
 * of `byWord` names wins where the line contains that word, else none.
 */
describe('POST /api/web/v1/sessions/<syncId>/points with a decision service (#101, #136)', () => {
  let server: Server;
  let base = '';
  let calls: Batch[] = [];
  let byWord: Record<string, string> = {};
  let status = 200;

  beforeAll(async () => {
    server = createServer((req, res) => {
      let body = '';
      req.on('data', (c: Buffer) => (body += c.toString()));
      req.on('end', () => {
        const batch = JSON.parse(body) as Batch;
        calls.push(batch);
        if (status !== 200) {
          res.writeHead(status);
          res.end('{"detail":"down"}');
          return;
        }
        const results = batch.states.map((state) => ({
          answers: Object.fromEntries(
            Object.entries(batch.questions).map(([id, q]) => {
              const label = Object.entries(q.criteria).find(([, text]) => Object.entries(byWord).some(([word, point]) => state.includes(word) && text === point))?.[0];
              const choice = label ?? NONE_KEY;
              return [id, { type: 'choice', choice, probabilities: Object.fromEntries(Object.keys(q.criteria).map((k) => [k, k === choice ? 0.9 : 0.01])) }];
            }),
          ),
          usage: { input_tokens: 10, truncated: false },
        }));
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ results }));
      });
    });
    await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
    base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  });

  const withDecision = (env: Record<string, string> = {}) => {
    calls = [];
    byWord = {};
    status = 200;
    stubServerEnv({ LLM_UPSTREAM_URL: process.env.LLM_UPSTREAM_URL!, LLM_MODEL: 'm', DECISION_UPSTREAM_URL: base, DECISION_FOR_TALKING_POINTS: 'on', ...env });
  };

  it('uses the language model, not the decision service, unless switched on (#136)', async () => {
    withDecision({ DECISION_FOR_TALKING_POINTS: '' });
    byWord = { 'how are you': 'How Bob is' };
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision + 1, ticked: 1 }]);
    expect(calls).toHaveLength(0);
    expect(llm.calls).toHaveLength(1);
  });

  it('is not offered with a decision service alone unless switched on (#136)', async () => {
    withDecision({ LLM_UPSTREAM_URL: '', LLM_MODEL: '', DECISION_FOR_TALKING_POINTS: 'off' });
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    const res = await check('s1', { baseRevision: pushed.revision });
    expect(res.status).toBe(404);
    expect(calls).toHaveLength(0);
  });

  it('uses only what the user was given (#145)', async () => {
    withDecision();
    byWord = { 'how are you': 'How Bob is' };
    await noteActivity(OWNER);
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    // Without the decision service, the language model checks them.
    await setServices(OWNER, ['sync', 'llm']);
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision + 1, ticked: 1 }]);
    expect(calls).toHaveLength(0);
    expect(llm.calls).toHaveLength(1);
    // With neither, nothing does.
    await setServices(OWNER, ['sync']);
    const res = await check('s1', { baseRevision: pushed.revision + 1 });
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: 'service_not_allowed', service: 'decision' });
    withDecision({ DECISION_FOR_TALKING_POINTS: 'off' });
    expect(await (await check('s1', { baseRevision: pushed.revision + 1 })).json()).toEqual({ error: 'service_not_allowed', service: 'llm' });
  });

  it('asks the decision service a line at a time, not the language model, and ticks at the line', async () => {
    withDecision({ DECISION_MODEL: 'english' });
    byWord = { 'how are you': 'How Bob is' };
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision + 1, ticked: 1 }]);
    expect(llm.calls).toEqual([]);
    expect(calls).toHaveLength(1);
    expect(calls[0].states).toEqual(['[00:00] Alice: Hello there.', '[00:04] Bob: Hi, how are you?']);
    expect(calls[0].model).toBe('english');
    // The open points, then the settled ones to fill the question, then none.
    expect(calls[0].questions.points1.criteria).toEqual({ 1: 'Greetings', 2: 'How Bob is', 3: 'Budget', 4: 'Hiring', 5: 'Office', [NONE_KEY]: NONE_TEXT });
    const { manifest } = await manifestOf(OWNER, 's1');
    expect(manifest.talkingPoints).toEqual([POINTS[0], { ...POINTS[1], covered: true, coveredAtMs: 4000 }, POINTS[2], POINTS[3], POINTS[4]]);
  });

  it('never ticks a settled point it names', async () => {
    withDecision();
    byWord = { Hello: 'Budget' };
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision, ticked: 0 }]);
  });

  it('sends a long transcript in batches of 64 lines, with a step for each', async () => {
    withDecision();
    const lines = Array.from({ length: 130 }, (_, i) => ({ startMs: i * 1000, endMs: i * 1000 + 900, text: i === 100 ? 'Now the budget.' : `line ${i}`, speakerIndex: 0, tokens: [] }));
    byWord = { budget: 'Second' };
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { lines, talkingPoints: [point('a', 'First'), point('b', 'Second')] }));
    const out = await events(await check('s1', { baseRevision: pushed.revision }));
    expect(out.filter((e) => 'step' in e)).toEqual([1, 2, 3].map((part) => ({ step: { part, parts: 3 } })));
    expect(calls.map((c) => c.states.length)).toEqual([64, 64, 2]);
    expect(out.at(-1)).toEqual({ done: true, revision: pushed.revision + 1, ticked: 1 });
    expect((await manifestOf(OWNER, 's1')).manifest.talkingPoints[1]).toMatchObject({ covered: true, coveredAtMs: 100_000 });
  });

  it('works with a decision service and no language model, and reports its failure', async () => {
    withDecision({ LLM_UPSTREAM_URL: '', LLM_MODEL: '' });
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { talkingPoints: POINTS }));
    status = 502;
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ error: 'upstream_error' }]);
    status = 200;
    expect(await events(await check('s1', { baseRevision: pushed.revision }))).toEqual([{ done: true, revision: pushed.revision, ticked: 0 }]);
  });
});
