import { createServer, type IncomingMessage, type Server, type ServerResponse } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, beforeAll, beforeEach, vi } from 'vitest';
import { resetAiProxy } from '@/lib/ai-proxy';
import { getDocumentMeta, readManifest } from '@/lib/documents';
import { resetLlmClient } from '@/lib/llm-client';
import { stubServerEnv } from './env';

export type ChatMessage = { role: string; content: string };
export type Reply = (messages: ChatMessage[], res: ServerResponse) => void | Promise<void>;

/** `text` as an OpenAI-compatible streamed answer, in pieces of five characters. */
export const sse = (text: string) =>
  (text.match(/[\s\S]{1,5}/g) ?? []).map((p) => `data: ${JSON.stringify({ choices: [{ delta: { content: p } }] })}\n\n`).join('') + 'data: [DONE]\n\n';

/** A reply that streams `text`. */
export const answer =
  (text: string): Reply =>
  (_messages, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    res.end(sse(text));
  };

/**
 * A fake language model for the web app's language-model routes, set up as
 * the server's upstream before each test: `calls` collects the messages of
 * each chat call, `bodies` its whole request, and `reply` (reset to
 * `initial` before each test) answers it.
 */
export function fakeLlm(initial: Reply, env: Record<string, string> = {}) {
  let server: Server;
  let base = '';
  const llm = { calls: [] as ChatMessage[][], bodies: [] as Record<string, unknown>[], reply: initial };

  beforeAll(async () => {
    server = createServer((req: IncomingMessage, res) => {
      let body = '';
      req.on('data', (chunk: Buffer) => (body += chunk.toString()));
      req.on('end', () => {
        const parsed = JSON.parse(body) as { messages: ChatMessage[] };
        llm.calls.push(parsed.messages);
        llm.bodies.push(parsed);
        void llm.reply(parsed.messages, res);
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
    llm.calls = [];
    llm.bodies = [];
    llm.reply = initial;
    resetLlmClient();
    vi.spyOn(console, 'warn').mockImplementation(() => {});
    stubServerEnv({ LLM_UPSTREAM_URL: `${base}/v1`, LLM_MODEL: 'm', ...env });
  });

  return llm;
}

/** The NDJSON events of a streamed answer. */
export async function events(res: Response) {
  return (await res.text())
    .split('\n')
    .filter(Boolean)
    .map((line) => JSON.parse(line) as Record<string, unknown>);
}

/** A session's current revision and parsed manifest. */
export async function manifestOf(owner: string, syncId: string) {
  const meta = (await getDocumentMeta(owner, 'session', syncId))!;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return { meta, manifest: JSON.parse(Buffer.from(await readManifest(owner, 'session', syncId, meta.manifestHash!)).toString('utf8')) as any };
}
