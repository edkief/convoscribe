import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { stubServerEnv } from '@/test/env';
import { proxyToUpstream, resetAiProxy, upstreamOf } from './ai-proxy';
import { HttpError } from './http';

const UPSTREAM_KEY = 'upstream-secret-key';
const CALLER_KEY = 'caller-sync-key-0123456789abcdef';

type Handler = (req: IncomingMessage, res: ServerResponse) => void;

let server: Server;
let base: string;
let handler: Handler;
let seen: { method?: string; url?: string; headers: IncomingMessage['headers']; body: Buffer }[];

beforeAll(async () => {
  server = createServer((req, res) => handler(req, res));
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}/v1`;
});

afterAll(async () => {
  server.closeAllConnections();
  await new Promise((resolve) => server.close(resolve));
});

/** Answer with `status` and `body` after recording the request. */
const answer =
  (status: number, body: string, type = 'application/json'): Handler =>
  (req, res) => {
    const chunks: Buffer[] = [];
    req.on('data', (chunk: Buffer) => chunks.push(chunk));
    req.on('end', () => {
      seen.push({ method: req.method, url: req.url, headers: req.headers, body: Buffer.concat(chunks) });
      res.writeHead(status, { 'content-type': type, 'x-upstream-internal': 'secret' });
      res.end(body);
    });
  };

beforeEach(async () => {
  seen = [];
  handler = answer(200, '{"ok":true}');
  stubServerEnv({ LLM_UPSTREAM_URL: base, LLM_UPSTREAM_KEY: UPSTREAM_KEY, LLM_MODEL: 'small', ASR_UPSTREAM_URL: base });
  await resetAiProxy();
});

afterEach(async () => {
  vi.unstubAllEnvs();
  vi.restoreAllMocks();
  await resetAiProxy();
});

const post = (body: BodyInit, headers: Record<string, string> = {}, signal?: AbortSignal) =>
  new Request('http://server/api/ai/v1/llm/chat/completions', {
    method: 'POST',
    headers: { authorization: `Bearer ${CALLER_KEY}`, 'content-type': 'application/json', cookie: 'a=b', ...headers },
    body,
    signal,
    duplex: 'half',
  } as RequestInit);

describe('upstreamOf', () => {
  it('reports each configured upstream, and null for one that is not', () => {
    expect(upstreamOf('llm')).toEqual({ url: base, key: UPSTREAM_KEY, model: 'small' });
    expect(upstreamOf('asr')).toEqual({ url: base, key: undefined, model: undefined });
    expect(upstreamOf('decision')).toBeNull();
    stubServerEnv({ DECISION_UPSTREAM_URL: 'http://laya.llama:8080', DECISION_MODEL: 'english' });
    expect(upstreamOf('decision')).toEqual({ url: 'http://laya.llama:8080', key: undefined, model: 'english' });
    stubServerEnv();
    expect(upstreamOf('llm')).toBeNull();
    expect(upstreamOf('asr')).toBeNull();
    expect(upstreamOf('decision')).toBeNull();
  });
});

describe('proxyToUpstream', () => {
  it('sends the request to the fixed path with the upstream key in place of the caller’s', async () => {
    const res = await proxyToUpstream(post('{"model":"small"}', { 'x-forwarded-for': '10.0.0.1' }), 'llm', '/chat/completions');
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ ok: true });
    expect(seen).toHaveLength(1);
    expect(seen[0].method).toBe('POST');
    expect(seen[0].url).toBe('/v1/chat/completions');
    expect(seen[0].headers.authorization).toBe(`Bearer ${UPSTREAM_KEY}`);
    expect(seen[0].headers['content-type']).toBe('application/json');
    expect(seen[0].headers.cookie).toBeUndefined();
    expect(seen[0].headers['x-forwarded-for']).toBeUndefined();
    expect(seen[0].body.toString()).toBe('{"model":"small"}');
  });

  it('sends no Authorization at all when the upstream has no key', async () => {
    await proxyToUpstream(post('x'), 'asr', '/audio/transcriptions');
    expect(seen[0].url).toBe('/v1/audio/transcriptions');
    expect(seen[0].headers.authorization).toBeUndefined();
    expect(JSON.stringify(seen[0].headers)).not.toContain(CALLER_KEY);
  });

  it('passes a GET through without a body', async () => {
    const res = await proxyToUpstream(
      new Request('http://server/api/ai/v1/llm/models', { headers: { authorization: `Bearer ${CALLER_KEY}` } }),
      'llm',
      '/models',
    );
    expect(res.status).toBe(200);
    expect(seen[0]).toMatchObject({ method: 'GET', url: '/v1/models' });
  });

  it('passes back only the content type, and marks the answer not to be transformed', async () => {
    const res = await proxyToUpstream(post('x'), 'llm', '/chat/completions');
    expect(res.headers.get('content-type')).toBe('application/json');
    expect(res.headers.get('cache-control')).toContain('no-transform');
    expect(res.headers.get('x-upstream-internal')).toBeNull();
  });

  it('is 404 for a service with no upstream', async () => {
    stubServerEnv();
    await expect(proxyToUpstream(post('x'), 'llm', '/chat/completions')).rejects.toMatchObject(
      new HttpError(404, 'not_offered'),
    );
  });

  it('streams the answer as the upstream writes it', async () => {
    let finish!: () => void;
    handler = (req, res) => {
      req.resume();
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: one\n\n');
        finish = () => res.end('data: [DONE]\n\n');
      });
    };
    const res = await proxyToUpstream(post('x'), 'llm', '/chat/completions');
    const reader = res.body!.getReader();
    const first = await reader.read();
    expect(new TextDecoder().decode(first.value)).toBe('data: one\n\n');
    finish();
    let rest = '';
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      rest += new TextDecoder().decode(value);
    }
    expect(rest).toBe('data: [DONE]\n\n');
  });

  it('streams the upload: the upstream gets the first part before the caller sends the rest', async () => {
    let gotFirst!: () => void;
    const firstArrived = new Promise<void>((resolve) => (gotFirst = resolve));
    handler = (req, res) => {
      const chunks: Buffer[] = [];
      req.on('data', (chunk: Buffer) => {
        chunks.push(chunk);
        gotFirst();
      });
      req.on('end', () => res.end(String(Buffer.concat(chunks).length)));
    };
    const part = new Uint8Array(64 * 1024).fill(7);
    let controller!: ReadableStreamDefaultController<Uint8Array>;
    const body = new ReadableStream<Uint8Array>({ start: (c) => void (controller = c) });
    controller.enqueue(part);
    const pending = proxyToUpstream(post(body, { 'content-type': 'audio/wav' }), 'asr', '/audio/transcriptions');
    await firstArrived;
    controller.enqueue(part);
    controller.close();
    const res = await pending;
    expect(await res.text()).toBe(String(2 * part.length));
  });

  it('cancels the upstream call when the caller goes away', async () => {
    let upstreamClosed!: () => void;
    const closed = new Promise<void>((resolve) => (upstreamClosed = resolve));
    handler = (req, res) => {
      req.resume();
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: one\n\n');
        res.on('close', upstreamClosed);
      });
    };
    const caller = new AbortController();
    const res = await proxyToUpstream(post('x', {}, caller.signal), 'llm', '/chat/completions');
    const reader = res.body!.getReader();
    await reader.read();
    caller.abort();
    await closed;
  });

  it('cancels the upstream call when the caller stops reading the answer', async () => {
    let upstreamClosed!: () => void;
    const closed = new Promise<void>((resolve) => (upstreamClosed = resolve));
    handler = (req, res) => {
      req.resume();
      req.on('end', () => {
        res.writeHead(200, { 'content-type': 'text/event-stream' });
        res.write('data: one\n\n');
        res.on('close', upstreamClosed);
      });
    };
    const res = await proxyToUpstream(post('x'), 'llm', '/chat/completions');
    await res.body!.cancel();
    await closed;
  });

  it.each([400, 404, 413, 422, 429])('passes an upstream %i back as it is', async (status) => {
    handler = answer(status, '{"error":{"message":"model not found"}}');
    const res = await proxyToUpstream(post('x'), 'llm', '/chat/completions');
    expect(res.status).toBe(status);
    expect(await res.json()).toEqual({ error: { message: 'model not found' } });
  });

  /** A 502 with `code`, saying nothing about the upstream; the detail goes to the log. */
  async function expectFailure(res: Response, code: string, logged: RegExp) {
    expect(res.status).toBe(502);
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ error: code });
    expect(res.headers.get('www-authenticate')).toBeNull();
    for (const secret of [base, '127.0.0.1', UPSTREAM_KEY]) expect(text).not.toContain(secret);
    const log = vi.mocked(console.warn).mock.calls.flat().join('\n');
    expect(log).toMatch(logged);
    expect(log).not.toContain(UPSTREAM_KEY);
  }

  it.each([401, 403])('turns an upstream %i into a 502 the caller can’t take for its own key', async (status) => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    handler = answer(status, `{"error":"bad key ${UPSTREAM_KEY}"}`);
    await expectFailure(await proxyToUpstream(post('x'), 'llm', '/chat/completions'), 'upstream_rejected', /LLM_UPSTREAM_KEY/);
  });

  it.each([500, 503])('turns an upstream %i into a 502', async (status) => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    handler = answer(status, `upstream ${base} fell over`);
    await expectFailure(await proxyToUpstream(post('x'), 'llm', '/chat/completions'), 'upstream_error', /fell over/);
  });

  it('turns a redirect into a 502 rather than following it', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    handler = (req, res) => {
      req.resume();
      req.on('end', () => {
        res.writeHead(302, { location: 'http://elsewhere.example/' });
        res.end();
      });
    };
    await expectFailure(await proxyToUpstream(post('x'), 'llm', '/chat/completions'), 'upstream_error', /302/);
  });

  it('turns an unreachable upstream into a 502', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    const closed = createServer();
    await new Promise<void>((resolve) => closed.listen(0, '127.0.0.1', resolve));
    const port = (closed.address() as AddressInfo).port;
    await new Promise((resolve) => closed.close(resolve));
    stubServerEnv({ LLM_UPSTREAM_URL: `http://127.0.0.1:${port}/v1`, LLM_UPSTREAM_KEY: UPSTREAM_KEY });
    await expectFailure(await proxyToUpstream(post('x'), 'llm', '/chat/completions'), 'upstream_unreachable', /unreachable/);
  });

  it('turns an upstream that never answers into a 502 after the timeout', async () => {
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    await resetAiProxy({ timeoutMs: 200 });
    handler = (req) => req.resume();
    await expectFailure(await proxyToUpstream(post('x'), 'llm', '/chat/completions'), 'upstream_unreachable', /unreachable/);
  });
});
