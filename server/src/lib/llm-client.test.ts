import { createServer, type IncomingMessage, type ServerResponse, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';
import { stubServerEnv } from '@/test/env';
import { resetAiProxy } from './ai-proxy';
import { chatStream, LlmError, parseSseLine, resetLlmClient, resolveModel } from './llm-client';

let server: Server;
let base: string;
let handler: (req: IncomingMessage, body: string, res: ServerResponse) => void;
let seen: { url?: string; authorization?: string; body: string }[];

beforeAll(async () => {
  server = createServer((req, res) => {
    let body = '';
    req.on('data', (chunk: Buffer) => (body += chunk.toString()));
    req.on('end', () => {
      seen.push({ url: req.url, authorization: req.headers.authorization, body });
      handler(req, body, res);
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
  resetLlmClient();
  vi.spyOn(console, 'warn').mockImplementation(() => {});
  stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_UPSTREAM_KEY: 'llm-key', LLM_MODEL: 'big' });
});

const sse = (...pieces: string[]) =>
  pieces.map((p) => `data: ${JSON.stringify({ choices: [{ delta: { content: p } }] })}\n\n`).join('') + 'data: [DONE]\n\n';

async function collect(gen: AsyncGenerator<string>) {
  const out: string[] = [];
  for await (const piece of gen) out.push(piece);
  return out;
}

describe('parseSseLine', () => {
  it('reads content, the end and nothing else', () => {
    expect(parseSseLine('data: {"choices":[{"delta":{"content":"Hi"}}]}')).toBe('Hi');
    expect(typeof parseSseLine('data: [DONE]')).toBe('symbol');
    expect(parseSseLine(': keep-alive')).toBeNull();
    expect(parseSseLine('data: {"choices":[{"delta":{"role":"assistant"}}]}')).toBeNull();
    expect(parseSseLine('data: not json')).toBeNull();
  });

  it('fails on an error frame', () => {
    expect(() => parseSseLine('data: {"error":{"message":"boom"}}')).toThrow(LlmError);
  });
});

describe('chatStream', () => {
  it('streams the reply with the model, key and messages', async () => {
    handler = (_req, _body, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.end(sse('Hel', 'lo'));
    };
    expect(await collect(chatStream([{ role: 'user', content: 'Hi' }]))).toEqual(['Hel', 'lo']);
    expect(seen[0].url).toBe('/v1/chat/completions');
    expect(seen[0].authorization).toBe('Bearer llm-key');
    expect(JSON.parse(seen[0].body)).toEqual({ model: 'big', stream: true, temperature: 0.3, messages: [{ role: 'user', content: 'Hi' }] });
  });

  it('asks for low reasoning the server’s way only when told to (#136)', async () => {
    handler = (_req, _body, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.end(sse('ok'));
    };
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'big', LLM_LOW_REASONING: '{"chat_template_kwargs":{"enable_thinking":false}}' });
    await collect(chatStream([{ role: 'user', content: 'a' }], undefined, { lowReasoning: true }));
    await collect(chatStream([{ role: 'user', content: 'a' }]));
    expect(JSON.parse(seen[0].body).chat_template_kwargs).toEqual({ enable_thinking: false });
    expect(JSON.parse(seen[1].body).chat_template_kwargs).toBeUndefined();
  });

  it('takes the first listed model when none is set, once', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1` });
    handler = (req, _body, res) => {
      if (req.url === '/v1/models') {
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end(JSON.stringify({ data: [{ id: 'listed' }, { id: 'other' }] }));
        return;
      }
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.end(sse('ok'));
    };
    await collect(chatStream([{ role: 'user', content: 'a' }]));
    await collect(chatStream([{ role: 'user', content: 'b' }]));
    expect(seen.map((s) => s.url)).toEqual(['/v1/models', '/v1/chat/completions', '/v1/chat/completions']);
    expect(JSON.parse(seen[1].body).model).toBe('listed');
    expect(seen[0].authorization).toBeUndefined();
  });

  it('names no model when the upstream lists none', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1` });
    handler = (req, _body, res) => {
      res.writeHead(200, { 'content-type': req.url === '/v1/models' ? 'application/json' : 'text/event-stream' });
      res.end(req.url === '/v1/models' ? '{"data":[]}' : sse('ok'));
    };
    await collect(chatStream([{ role: 'user', content: 'a' }]));
    expect(JSON.parse(seen[1].body).model).toBeUndefined();
  });

  it.each([
    [401, 'upstream_rejected'],
    [403, 'upstream_rejected'],
    [400, 'upstream_error'],
    [500, 'upstream_error'],
  ])('maps an upstream %i to %s', async (status, code) => {
    handler = (_req, _body, res) => {
      res.writeHead(status);
      res.end('nope');
    };
    await expect(collect(chatStream([{ role: 'user', content: 'a' }]))).rejects.toMatchObject({ code });
  });

  it('is unreachable without a connection', async () => {
    stubServerEnv({ LLM_UPSTREAM_URL: 'http://127.0.0.1:1/v1', LLM_MODEL: 'm' });
    await expect(collect(chatStream([{ role: 'user', content: 'a' }]))).rejects.toMatchObject({ code: 'upstream_unreachable' });
  });

  it('is not offered without an upstream', async () => {
    stubServerEnv();
    await expect(resolveModel()).rejects.toMatchObject({ code: 'not_offered' });
  });

  it('stops when aborted', async () => {
    handler = (_req, _body, res) => {
      res.writeHead(200, { 'content-type': 'text/event-stream' });
      res.write(sse('first').replace('data: [DONE]\n\n', ''));
    };
    const controller = new AbortController();
    const gen = chatStream([{ role: 'user', content: 'a' }], controller.signal);
    expect((await gen.next()).value).toBe('first');
    controller.abort();
    await expect(gen.next()).rejects.toThrow();
  });
});
