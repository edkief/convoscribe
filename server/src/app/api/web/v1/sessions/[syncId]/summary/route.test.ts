import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { resetAiProxy } from '@/lib/ai-proxy';
import { getDocumentMeta, readManifest } from '@/lib/documents';
import { resetLlmClient } from '@/lib/llm-client';
import { TITLE_SYSTEM } from '@/lib/assist-prompt';
import { PART_NOTES_SYSTEM } from '@/lib/summary-prompt';
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

const SUMMARY = '**Overview**\nWe met.\n\n**Action items**\n- Alice: send the deck\n';
const REPLY = SUMMARY + '\n```json\n[{"text": "Send the deck", "owner": "alice", "due": null, "at": "00:04"}]\n```';

beforeEach(() => {
  calls = [];
  reply = answer(REPLY);
  resetLlmClient();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'm' });
});

const summarise = (syncId: string, body: unknown, headers: Record<string, string> = ORIGIN) =>
  POST(request(`/api/web/v1/sessions/${syncId}/summary`, { method: 'POST', body: JSON.stringify(body), headers }), ctx({ syncId }));

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

describe('POST /api/web/v1/sessions/<syncId>/summary (#117)', () => {
  it('streams the summary and saves it with its action items as a new revision', async () => {
    const pushed = await pushManifest(
      OWNER,
      sessionManifest('s1', {
        messages: [
          { kind: 'SUMMARY', role: 'ASSISTANT', content: 'old', createdAt: 1 },
          { kind: 'QA', role: 'USER', content: 'q?', createdAt: 2 },
        ],
        actionItems: [
          { text: 'Book the room', extractedText: 'Book the room', createdAt: 1 },
          { text: 'Call the bank', extractedText: 'Call the bank', done: true, createdAt: 1 },
        ],
        marks: [{ atMs: 5000, note: 'deck', createdAt: 1 }],
        future: { kept: true },
      }),
    );
    const res = await summarise('s1', { baseRevision: pushed.revision, zone: 'Europe/Paris' });
    expect(res.status).toBe(200);
    expect(res.headers.get('content-type')).toContain('application/x-ndjson');
    const out = await events(res);
    expect(out.at(-1)).toEqual({ done: true, revision: pushed.revision + 1 });
    expect(out.at(-2)).toEqual({ text: SUMMARY.trimEnd() });
    expect(out.some((e) => typeof e.text === 'string' && (e.text as string).includes('```'))).toBe(false);

    expect(calls).toHaveLength(1);
    expect(calls[0][1].content).toMatch(/^The conversation was recorded on 2025-10-09 \(Thursday\)\.\n\n/);
    expect(calls[0][1].content).toContain('[00:05] deck');
    expect(calls[0][1].content).toContain('[00:00] Alice: Hello there.\n[00:04] Bob: Hi, how are you?');

    const { meta, manifest } = await manifestOf('s1');
    expect(meta.revision).toBe(pushed.revision + 1);
    expect(manifest.messages).toEqual([
      { kind: 'QA', role: 'USER', content: 'q?', createdAt: 2 },
      { kind: 'SUMMARY', role: 'ASSISTANT', content: SUMMARY.trimEnd(), createdAt: expect.any(Number), templateName: 'Meeting' },
    ]);
    expect(manifest.actionItems.map((a: { text: string }) => a.text)).toEqual(['Call the bank', 'Send the deck']);
    expect(manifest.actionItems[1]).toMatchObject({ owner: 'Alice', atMs: 4000, extractedText: 'Send the deck', done: false });
    expect(manifest.summaryTemplate).toEqual({ key: 'meeting' });
    expect(manifest.future).toEqual({ kept: true });
  });

  it('uses the chosen template, a synced one by name', async () => {
    await pushManifest(OWNER, { schema: 1, syncId: 't1', name: 'Standup', body: 'Three bullets.', createdAt: 1 }, 0, 'template');
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    const out = await events(await summarise('s1', { baseRevision: pushed.revision, template: 'user:Standup' }));
    expect(out.at(-1)).toMatchObject({ done: true });
    expect(calls[0][0].content).toContain('Three bullets.');
    const { manifest } = await manifestOf('s1');
    expect(manifest.summaryTemplate).toEqual({ user: 'Standup' });
    expect(manifest.messages.at(-1).templateName).toBe('Standup');
  });

  it("uses the session's template when none is chosen, and Meeting for an unknown one", async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { summaryTemplate: { key: 'brief' } }));
    await events(await summarise('s1', { baseRevision: pushed.revision }));
    expect(calls[0][0].content).toContain('Write only 3 to 5 bullet points');
    await events(await summarise('s1', { baseRevision: pushed.revision + 1, template: 'gone' }));
    expect(calls[1][0].content).toContain('1. Overview: one short paragraph.');
  });

  it('summarises a long transcript in parts', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'm', LLM_MAX_TRANSCRIPT_CHARS: '1000' });
    const lines = Array.from({ length: 60 }, (_, i) => ({ startMs: i * 60_000, endMs: i * 60_000 + 50_000, text: `we agreed on fact${i} and talked about it.`, speakerIndex: 0, tokens: [] }));
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { lines }));
    reply = (messages, res) => answer(messages[0].content === PART_NOTES_SYSTEM ? 'notes' : REPLY)(messages, res);
    const out = await events(await summarise('s1', { baseRevision: pushed.revision }));
    const steps = out.filter((e) => 'step' in e).map((e) => e.step);
    expect(steps[0]).toEqual({ part: 1, parts: expect.any(Number) });
    expect(steps.at(-1)).toBe('writing');
    expect(calls.length).toBe(steps.length);
    expect(out.at(-1)).toMatchObject({ done: true });
  });

  it('saves nothing when the session changed while it was written', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    reply = async (messages, res) => {
      await pushManifest(OWNER, sessionManifest('s1', { title: 'Renamed on a device' }), pushed.revision);
      answer(REPLY)(messages, res);
    };
    const out = await events(await summarise('s1', { baseRevision: pushed.revision }));
    expect(out.at(-1)).toEqual({ error: 'conflict' });
    const { manifest } = await manifestOf('s1');
    expect(manifest.title).toBe('Renamed on a device');
    expect(manifest.messages).toEqual([]);
  });

  it('reports an upstream failure and saves nothing', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    reply = (_m, res) => {
      res.writeHead(500);
      res.end('down');
    };
    expect((await events(await summarise('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ error: 'upstream_error' });
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('saves nothing for an empty reply', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    reply = answer('```json\n[]\n```');
    expect((await events(await summarise('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ error: 'empty_reply' });
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('saves nothing when the page stops it', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    let finish!: () => void;
    reply = (_m, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(sse('Partial summary').replace('data: [DONE]\n\n', ''));
      finish = () => res.end(sse(' and the rest'));
    };
    const res = await summarise('s1', { baseRevision: pushed.revision });
    const reader = res.body!.getReader();
    await reader.read();
    await reader.cancel();
    finish();
    await new Promise((resolve) => setTimeout(resolve, 100));
    expect((await getDocumentMeta(OWNER, 'session', 's1'))!.revision).toBe(pushed.revision);
  });

  it('names a session with its date-based title from the summary, in the same revision (#119)', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { title: 'Recording 2025-10-09 10:00', titleSource: 'default', language: 'fr' }));
    reply = (messages, res) => answer(messages[0].content.startsWith(TITLE_SYSTEM) ? 'Titre : « Le deck »' : REPLY)(messages, res);
    const out = await events(await summarise('s1', { baseRevision: pushed.revision }));
    expect(out).toContainEqual({ step: 'naming' });
    expect(out.at(-1)).toEqual({ done: true, revision: pushed.revision + 1 });
    expect(calls[1]).toEqual([
      { role: 'system', content: TITLE_SYSTEM + ' Write it in French.' },
      { role: 'user', content: 'Summary:\n\n' + SUMMARY.trim() },
    ]);
    const { manifest } = await manifestOf('s1');
    expect(manifest).toMatchObject({ title: 'Le deck', titleSource: 'auto', previousTitle: 'Recording 2025-10-09 10:00' });
    expect(manifest.messages.at(-1).content).toBe(SUMMARY.trimEnd());
  });

  it('leaves a chosen or automatic title alone (#119)', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { titleSource: 'auto', previousTitle: 'x' }));
    const out = await events(await summarise('s1', { baseRevision: pushed.revision }));
    expect(out.some((e) => e.step === 'naming')).toBe(false);
    expect(calls).toHaveLength(1);
    expect((await manifestOf('s1')).manifest.title).toBe('Session s1');
  });

  it('saves the summary when naming the session fails (#119)', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1', { title: 'Recording', titleSource: 'default' }));
    reply = (messages, res) => {
      if (!messages[0].content.startsWith(TITLE_SYSTEM)) return answer(REPLY)(messages, res);
      res.writeHead(500);
      res.end('down');
    };
    expect((await events(await summarise('s1', { baseRevision: pushed.revision }))).at(-1)).toEqual({ done: true, revision: pushed.revision + 1 });
    const { manifest } = await manifestOf('s1');
    expect(manifest).toMatchObject({ title: 'Recording', titleSource: 'default' });
    expect(manifest.messages.at(-1).content).toBe(SUMMARY.trimEnd());
  });

  it('refuses what it cannot start, as a JSON error', async () => {
    const pushed = await pushManifest(OWNER, sessionManifest('s1'));
    await pushManifest(OWNER, sessionManifest('newer', { schema: 99 }));
    await pushManifest(OWNER, sessionManifest('quiet', { lines: [] }));
    const status = async (res: Response) => [res.status, (await res.json()).error];
    expect(await status(await summarise('s1', { baseRevision: pushed.revision + 1 }))).toEqual([409, 'conflict']);
    expect(await status(await summarise('newer', { baseRevision: 1 }))).toEqual([422, 'newer_schema']);
    expect(await status(await summarise('quiet', { baseRevision: 1 }))).toEqual([422, 'empty_transcript']);
    expect(await status(await summarise('nope', { baseRevision: 1 }))).toEqual([404, 'not_found']);
    expect(await status(await summarise('s1', { baseRevision: pushed.revision, template: 3 }))).toEqual([400, 'invalid_body']);
    expect(await status(await summarise('s1', { baseRevision: pushed.revision }, { 'content-type': 'application/json', origin: 'https://evil.example' }))).toEqual([403, 'cross_site']);
    expect((await summarise('s1', { baseRevision: pushed.revision }, { ...ORIGIN, authorization: 'Bearer wrong' })).status).toBe(401);
    stubServerEnv();
    expect(await status(await summarise('s1', { baseRevision: pushed.revision }))).toEqual([404, 'not_offered']);
    expect(calls).toEqual([]);
  });
});
