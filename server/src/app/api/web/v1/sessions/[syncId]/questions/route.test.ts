import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetAiProxy } from '@/lib/ai-proxy';
import { getDocumentMeta, readManifest } from '@/lib/documents';
import { resetLlmClient } from '@/lib/llm-client';
import { QA_EXCERPTS_PREFIX, QA_SYSTEM_PREFIX } from '@/lib/qa-prompt';
import { stubServerEnv } from '@/test/env';
import { ctx, request, setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { POST } from './route';

setupSyncServer();

const OWNER = 'default';
const ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };

let server: Server;
let base: string;
let reply: (messages: { role: string; content: string }[], res: ServerResponse) => void | Promise<void>;
let calls: { role: string; content: string }[][];

beforeAll(async () => {
  server = createServer((req: IncomingMessage, res) => {
    let body = '';
    req.on('data', (chunk: Buffer) => (body += chunk.toString()));
    req.on('end', () => {
      const { messages } = JSON.parse(body) as { messages: { role: string; content: string }[] };
      calls.push(messages);
      void reply(messages, res);
    });
  });
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});

afterAll(async () => {
  await resetAiProxy();
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
});

const sse = (text: string) =>
  text.match(/[\s\S]{1,5}/g)!.map((p) => `data: ${JSON.stringify({ choices: [{ delta: { content: p } }] })}\n\n`).join('') + 'data: [DONE]\n\n';

const answer = (text: string) => (_messages: unknown, res: ServerResponse) => {
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.end(sse(text));
};

const ANSWER = 'Alice said hello [00:00].';

beforeEach(() => {
  calls = [];
  reply = answer(ANSWER);
  resetLlmClient();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'm' });
});

const ask = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request(`/api/web/v1/sessions/${syncId}/questions`, { method: 'POST', body: JSON.stringify(body), headers }), ctx({ syncId }));

async function events(res: Response) {
  return (await res.text())
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as Record<string, unknown>);
}

async function manifestOf(syncId: string) {
  const meta = (await getDocumentMeta(OWNER, 'session', syncId))!;
  return { meta, manifest: JSON.parse(Buffer.from(await readManifest(OWNER, 'session', syncId, meta.manifestHash!)).toString('utf8')) };
}

describe('POST /api/web/v1/sessions/<syncId>/questions (#118)', () => {
  it('streams the answer, with the earlier rounds as the conversation, and saves the round as a new revision', async () => {
    const earlier = [
      { kind: 'SUMMARY', role: 'ASSISTANT', content: 'sum', createdAt: 1 },
      { kind: 'QA', role: 'USER', content: 'Who spoke?', createdAt: 2 },
      { kind: 'QA', role: 'ASSISTANT', content: 'Alice and Bob.', createdAt: 3 },
    ];
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { messages: earlier, future: { kept: true } }));
    const res = await ask('s1', { baseRevision: pushed.revision, question: '  What did Alice say?  ' });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/x-ndjson');
    const out = await events(res);
    expect(out.at(-1)).toEqual({ done: true, revision: pushed.revision + 1 });
    expect(out.at(-2)).toEqual({ text: ANSWER });

    expect(calls).toEqual([
      [
        { role: 'system', content: QA_SYSTEM_PREFIX + '[00:00] Alice: Hello there.\n[00:04] Bob: Hi, how are you?' },
        { role: 'user', content: 'Who spoke?' },
        { role: 'assistant', content: 'Alice and Bob.' },
        { role: 'user', content: 'What did Alice say?' },
      ],
    ]);

    const { meta, manifest } = await manifestOf('s1');
    expect(meta.revision).toBe(pushed.revision + 1);
    expect(manifest.messages.slice(0, 3)).toEqual(earlier);
    expect(manifest.messages.slice(3)).toEqual([
      { kind: 'QA', role: 'USER', content: 'What did Alice say?', createdAt: expect.any(Number) },
      { kind: 'QA', role: 'ASSISTANT', content: ANSWER, createdAt: expect.any(Number) },
    ]);
    expect(manifest.messages[4].createdAt).toBeGreaterThan(manifest.messages[3].createdAt);
    expect(manifest.future).toEqual({ kept: true });
  });

  it('answers a long transcript from its most relevant parts and records their coverage', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'm', LLM_MAX_TRANSCRIPT_CHARS: '1200' });
    const lines = Array.from({ length: 60 }, (_, i) => ({
      startMs: i * 60_000,
      endMs: i * 60_000 + 50_000,
      text: i === 40 ? 'the Kowalski invoice is overdue.' : `we talked about the weather and the traffic, item ${i}.`,
      speakerIndex: 0,
      tokens: [],
    }));
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { lines, durationMs: 3_600_000 }));
    const out = await events(await ask('s1', { baseRevision: pushed.revision, question: 'What about Kowalski?' }));
    expect(out.at(-1)).toMatchObject({ done: true });
    expect(calls[0][0].content.startsWith(QA_EXCERPTS_PREFIX)).toBe(true);
    expect(calls[0][0].content).toContain('Kowalski invoice');
    expect(calls[0][0].content.length).toBeLessThanOrEqual(QA_EXCERPTS_PREFIX.length + 1200);
    const saved = (await manifestOf('s1')).manifest.messages.at(-1);
    expect(saved.totalMs).toBe(59 * 60_000 + 50_000);
    expect(saved.coveredMs).toBeGreaterThan(0);
    expect(saved.coveredMs).toBeLessThan(saved.totalMs);
  });

  it('saves nothing when the session changed while it was answered', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    reply = async (messages, res) => {
      await pushManifest(OWNER, sessionManifest('s1', { title: 'Renamed on a device' }), pushed.revision);
      answer(ANSWER)(messages, res);
    };
    const out = await events(await ask('s1', { baseRevision: pushed.revision, question: 'q?' }));
    expect(out.at(-1)).toEqual({ error: 'conflict' });
    const { manifest } = await manifestOf('s1');
    expect(manifest.title).toBe('Renamed on a device');
    expect(manifest.messages).toEqual([]);
  });

  it('reports an upstream failure or an empty answer and saves nothing', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    reply = (_m, res) => {
      res.writeHead(500);
      res.end('down');
    };
    expect((await events(await ask('s1', { baseRevision: pushed.revision, question: 'q?' }))).at(-1)).toEqual({ error: 'upstream_error' });
    reply = answer('  ');
    expect((await events(await ask('s1', { baseRevision: pushed.revision, question: 'q?' }))).at(-1)).toEqual({ error: 'empty_reply' });
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('saves nothing when the page stops it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    let finish!: () => void;
    reply = (_m, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(sse('Partial answer').replace('data: [DONE]\n\n', ''));
      finish = () => res.end(sse(' and the rest'));
    };
    const res = await ask('s1', { baseRevision: pushed.revision, question: 'q?' });
    const reader = res.body!.getReader();
    await reader.read();
    await reader.cancel();
    finish();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('refuses what it cannot start, as a JSON error', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    await pushManifest(OWNER, sessionManifest('newer', { schema: 99 }));
    await pushManifest(OWNER, sessionManifest('quiet', { lines: [] }));
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await ask('s1', { baseRevision: pushed.revision + 1, question: 'q?' }))).toEqual([409, 'conflict']);
    expect(await status(await ask('newer', { baseRevision: 1, question: 'q?' }))).toEqual([422, 'newer_schema']);
    expect(await status(await ask('quiet', { baseRevision: 1, question: 'q?' }))).toEqual([422, 'empty_transcript']);
    expect(await status(await ask('nope', { baseRevision: 1, question: 'q?' }))).toEqual([404, 'not_found']);
    expect(await status(await ask('s1', { baseRevision: pushed.revision, question: '   ' }))).toEqual([400, 'invalid_body']);
    expect(await status(await ask('s1', { baseRevision: pushed.revision }))).toEqual([400, 'invalid_body']);
    expect(await status(await ask('s1', { baseRevision: pushed.revision, question: 'q?' }, { 'content-type': 'application/json', origin: 'https://evil.example' }))).toEqual([403, 'cross_site']);
    expect((await ask('s1', { baseRevision: pushed.revision, question: 'q?' }, { ...ORIGIN, authorization: 'Bearer wrong' })).status).toBe(401);
    stubServerEnv();
    expect(await status(await ask('s1', { baseRevision: pushed.revision, question: 'q?' }))).toEqual([404, 'not_offered']);
    expect(calls).toEqual([]);
  });
});
