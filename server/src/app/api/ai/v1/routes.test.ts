import { createServer, type IncomingMessage, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { resetAiProxy } from '@/lib/ai-proxy';
import { stubServerEnv } from '@/test/env';
import { AUTH, request, setupSyncServer } from '@/test/http';
import { POST as transcribe } from './asr/audio/transcriptions/route';
import { GET as asrModels } from './asr/models/route';
import { GET as decisionHealth } from './decision/health/route';
import { POST as decideBatch } from './decision/v1/systemone/batch/route';
import { POST as decide } from './decision/v1/systemone/route';
import { POST as chat } from './llm/chat/completions/route';
import { GET as llmModels } from './llm/models/route';

setupSyncServer();

type Init = RequestInit & { headers?: Record<string, string> };

let server: Server;
let base: string;
let seen: { url?: string; authorization?: string; bytes: number }[];

beforeAll(async () => {
  server = createServer((req: IncomingMessage, res) => {
    let bytes = 0;
    req.on('data', (chunk: Buffer) => (bytes += chunk.length));
    req.on('end', () => {
      seen.push({ url: req.url, authorization: req.headers.authorization, bytes });
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ path: req.url, bytes }));
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

beforeEach(() => {
  seen = [];
  stubServerEnv({
    LLM_UPSTREAM_URL: `${base}/llm/v1`,
    LLM_UPSTREAM_KEY: 'llm-key',
    ASR_UPSTREAM_URL: `${base}/asr/v1`,
    DECISION_UPSTREAM_URL: `${base}/laya`,
    DECISION_UPSTREAM_KEY: 'laya-key',
  });
});

const routes = [
  { name: 'GET llm/models', call: (init: Init) => llmModels(request('/api/ai/v1/llm/models', init)), path: '/llm/v1/models' },
  {
    name: 'POST llm/chat/completions',
    call: (init: Init) => chat(request('/api/ai/v1/llm/chat/completions', { method: 'POST', body: '{}', ...init })),
    path: '/llm/v1/chat/completions',
  },
  { name: 'GET asr/models', call: (init: Init) => asrModels(request('/api/ai/v1/asr/models', init)), path: '/asr/v1/models' },
  {
    name: 'POST asr/audio/transcriptions',
    call: (init: Init) =>
      transcribe(request('/api/ai/v1/asr/audio/transcriptions', { method: 'POST', body: 'audio', ...init })),
    path: '/asr/v1/audio/transcriptions',
  },
  {
    name: 'POST decision/v1/systemone',
    call: (init: Init) => decide(request('/api/ai/v1/decision/v1/systemone', { method: 'POST', body: '{}', ...init })),
    path: '/laya/v1/systemone',
  },
  {
    name: 'POST decision/v1/systemone/batch',
    call: (init: Init) => decideBatch(request('/api/ai/v1/decision/v1/systemone/batch', { method: 'POST', body: '{}', ...init })),
    path: '/laya/v1/systemone/batch',
  },
  { name: 'GET decision/health', call: (init: Init) => decisionHealth(request('/api/ai/v1/decision/health', init)), path: '/laya/health' },
];

describe('/api/ai/v1 (#93)', () => {
  it.each(routes)('$name reaches its fixed upstream path', async ({ call, path }) => {
    const res = await call({});
    expect(res.status).toBe(200);
    expect((await res.json()).path).toBe(path);
    expect(seen).toHaveLength(1);
  });

  it.each(routes)('$name is 401 without a valid key, and never calls upstream', async ({ call }) => {
    for (const headers of [{ authorization: '' }, { authorization: 'Bearer wrong' }]) {
      const res = await call({ headers });
      expect(res.status).toBe(401);
      expect(res.headers.get('WWW-Authenticate')).toMatch(/^Bearer /);
    }
    expect(seen).toHaveLength(0);
  });

  it.each(routes)('$name is 404 when the server offers no upstream for it', async ({ call }) => {
    stubServerEnv();
    const res = await call({ headers: AUTH });
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: 'not_offered' });
  });

  it('sends the upstream key, or none, and never the caller’s', async () => {
    await chat(request('/api/ai/v1/llm/chat/completions', { method: 'POST', body: '{}' }));
    await transcribe(request('/api/ai/v1/asr/audio/transcriptions', { method: 'POST', body: 'x' }));
    await decideBatch(request('/api/ai/v1/decision/v1/systemone/batch', { method: 'POST', body: '{}' }));
    expect(seen.map((s) => s.authorization)).toEqual(['Bearer llm-key', undefined, 'Bearer laya-key']);
  });

  it('passes a large recording through in full', async () => {
    const size = 32 * 1024 * 1024;
    const chunk = new Uint8Array(1024 * 1024).fill(1);
    let sent = 0;
    const body = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (sent >= size) return controller.close();
        controller.enqueue(chunk);
        sent += chunk.length;
      },
    });
    const res = await transcribe(
      request('/api/ai/v1/asr/audio/transcriptions', {
        method: 'POST',
        body,
        headers: { 'content-type': 'multipart/form-data; boundary=x' },
        duplex: 'half',
      } as Init),
    );
    expect(res.status).toBe(200);
    expect((await res.json()).bytes).toBe(size);
  });
});
