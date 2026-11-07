import { fetch as undiciFetch } from 'undici';
import { agent, upstreamOf, withLowReasoning } from './ai-proxy';

/** One turn of a chat with the language model, as the app's `LlmMessage`. */
export type LlmMessage = { role: 'system' | 'user' | 'assistant'; content: string };

/** Why a call to the language model failed, as the AI proxy names it (#93). */
export type LlmErrorCode = 'not_offered' | 'upstream_rejected' | 'upstream_error' | 'upstream_unreachable';

export class LlmError extends Error {
  constructor(readonly code: LlmErrorCode) {
    super(code);
  }
}

/** The app's `OpenAiChatClient` temperature. */
const TEMPERATURE = 0.3;

let cachedModel: { url: string; model: string } | undefined;

/**
 * The model the web app's calls use: `LLM_MODEL`, else the first one the
 * upstream lists at `/models`, remembered until the upstream changes.
 */
export async function resolveModel(signal?: AbortSignal): Promise<string | undefined> {
  const upstream = upstreamOf('llm');
  if (!upstream) throw new LlmError('not_offered');
  if (upstream.model) return upstream.model;
  if (cachedModel?.url === upstream.url) return cachedModel.model;
  const res = await call('/models', { method: 'GET', signal });
  const body = (await res.json().catch(() => null)) as { data?: { id?: unknown }[] } | null;
  const id = body?.data?.find((m) => typeof m?.id === 'string')?.id as string | undefined;
  // An upstream serving one model may list none; it then needs no name.
  if (id) cachedModel = { url: upstream.url, model: id };
  return id;
}

/**
 * Stream the answer to `messages` from the server's language model, as the
 * app's `OpenAiChatClient.stream` does: each item is a piece of the reply's
 * text. Aborting `signal` cancels the upstream call. Failures throw
 * [LlmError], with the detail only in the log. `lowReasoning` asks the model
 * for little reasoning the server's way (`LLM_LOW_REASONING`, #136), as a
 * device's talking-point check does through the proxy.
 */
export async function* chatStream(
  messages: LlmMessage[],
  signal?: AbortSignal,
  options: { lowReasoning?: boolean } = {},
): AsyncGenerator<string> {
  const model = await resolveModel(signal);
  const request = { ...(model ? { model } : {}), stream: true, temperature: TEMPERATURE, messages };
  const res = await call('/chat/completions', {
    method: 'POST',
    headers: { 'content-type': 'application/json', accept: 'text/event-stream' },
    body: JSON.stringify(options.lowReasoning ? withLowReasoning(request) : request),
    signal,
  });
  if (!res.body) return;
  const decoder = new TextDecoder();
  let buffered = '';
  try {
    for await (const chunk of res.body) {
      buffered += decoder.decode(chunk as Uint8Array, { stream: true });
      let newline;
      while ((newline = buffered.indexOf('\n')) >= 0) {
        const line = buffered.slice(0, newline);
        buffered = buffered.slice(newline + 1);
        const frame = parseSseLine(line);
        if (frame === DONE) return;
        if (frame) yield frame;
      }
    }
  } catch (err) {
    if (signal?.aborted) throw err;
    console.warn(`ai proxy: web llm: stream broke off: ${describe(err)}`);
    throw new LlmError('upstream_unreachable');
  }
  const last = parseSseLine(buffered);
  if (last && last !== DONE) yield last;
}

const DONE = Symbol('done');

/**
 * One line of a server-sent event stream, as the app's `SseParser` reads it:
 * the text of `choices[0].delta.content` in a `data:` frame, [DONE] for
 * `data: [DONE]`, null for anything else. A frame carrying `error` fails.
 */
export function parseSseLine(raw: string): string | typeof DONE | null {
  const line = raw.trim();
  if (!line.startsWith('data:')) return null;
  const data = line.slice('data:'.length).trim();
  if (data === '[DONE]') return DONE;
  let frame: unknown;
  try {
    frame = JSON.parse(data);
  } catch {
    return null;
  }
  if (!frame || typeof frame !== 'object') return null;
  const f = frame as { error?: unknown; choices?: { delta?: { content?: unknown } }[] };
  if (f.error !== undefined && f.error !== null) {
    console.warn(`ai proxy: web llm: upstream sent an error: ${JSON.stringify(f.error).slice(0, 500)}`);
    throw new LlmError('upstream_error');
  }
  const content = f.choices?.[0]?.delta?.content;
  return typeof content === 'string' && content !== '' ? content : null;
}

async function call(path: '/models' | '/chat/completions', init: { method: string; headers?: Record<string, string>; body?: string; signal?: AbortSignal }) {
  const upstream = upstreamOf('llm');
  if (!upstream) throw new LlmError('not_offered');
  const headers: Record<string, string> = { ...init.headers, 'accept-encoding': 'identity' };
  if (upstream.key) headers.authorization = `Bearer ${upstream.key}`;
  let res;
  try {
    res = await undiciFetch(`${upstream.url}${path}`, { ...init, headers, redirect: 'manual', dispatcher: agent() });
  } catch (err) {
    if (init.signal?.aborted) throw err;
    console.warn(`ai proxy: web llm${path}: upstream unreachable: ${describe(err)}`);
    throw new LlmError('upstream_unreachable');
  }
  if (res.status === 401 || res.status === 403) {
    await res.body?.cancel();
    console.warn(`ai proxy: web llm${path}: upstream rejected the request (${res.status}); check LLM_UPSTREAM_KEY`);
    throw new LlmError('upstream_rejected');
  }
  if (!res.ok) {
    const detail = await res.text().catch(() => '');
    console.warn(`ai proxy: web llm${path}: upstream answered ${res.status}: ${detail.slice(0, 500)}`);
    throw new LlmError('upstream_error');
  }
  return res;
}

function describe(err: unknown): string {
  if (!(err instanceof Error)) return String(err);
  const cause = err.cause instanceof Error ? `: ${err.cause.message}` : '';
  return `${err.message}${cause}`;
}

/** Forget the model read from `/models` (tests only). */
export function resetLlmClient() {
  cachedModel = undefined;
}
