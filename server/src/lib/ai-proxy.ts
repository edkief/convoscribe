import { Agent, fetch as undiciFetch, type BodyInit as UndiciBody } from 'undici';
import { env } from './env';
import { HttpError } from './http';

/**
 * The upstreams the server offers its users (#93): a language model and a
 * transcription server, each an OpenAI-compatible API set in the
 * environment. Callers reach them only through the fixed routes under
 * /api/ai/v1; nothing a caller sends picks the upstream or its path.
 */
export type Service = 'llm' | 'asr';

/** The paths a caller may reach on each upstream. */
export type UpstreamPath = {
  llm: '/models' | '/chat/completions';
  asr: '/models' | '/audio/transcriptions';
};

export type Upstream = { url: string; key?: string; model?: string };

/** The configured upstream for `service`, or null when the server offers none. */
export function upstreamOf(service: Service): Upstream | null {
  const config = env();
  const [url, key, model] =
    service === 'llm'
      ? [config.LLM_UPSTREAM_URL, config.LLM_UPSTREAM_KEY, config.LLM_MODEL]
      : [config.ASR_UPSTREAM_URL, config.ASR_UPSTREAM_KEY, config.ASR_MODEL];
  return url ? { url, key, model } : null;
}

/** The app waits up to 15 minutes for an answer; undici's default is 5. */
const DEFAULT_TIMEOUT_MS = 15 * 60 * 1000;

let dispatcher: Agent | undefined;
let timeoutMs = DEFAULT_TIMEOUT_MS;

function agent(): Agent {
  dispatcher ??= new Agent({ headersTimeout: timeoutMs, bodyTimeout: timeoutMs });
  return dispatcher;
}

/** Request headers passed upstream; everything else, the caller's key included, stays here. */
const FORWARDED_REQUEST_HEADERS = ['content-type', 'content-length', 'accept'];

/** Upstream answers passed back as they are: the caller's request was at fault. */
const PASSED_THROUGH_ERRORS = new Set([400, 404, 413, 422, 429]);

/**
 * Send `req` to `path` on the upstream for `service` and stream its answer
 * back. The caller's `Authorization` is replaced by the upstream key (or
 * dropped when there is none), both bodies stream without buffering, and
 * the caller going away cancels the upstream call. Failures the caller
 * can't fix answer `502` with a code and no upstream detail; the detail
 * goes to the server log.
 */
export async function proxyToUpstream<S extends Service>(
  req: Request,
  service: S,
  path: UpstreamPath[S],
): Promise<Response> {
  const upstream = upstreamOf(service);
  if (!upstream) throw new HttpError(404, 'not_offered');

  const headers: Record<string, string> = {};
  for (const name of FORWARDED_REQUEST_HEADERS) {
    const value = req.headers.get(name);
    if (value !== null) headers[name] = value;
  }
  if (upstream.key) headers.authorization = `Bearer ${upstream.key}`;
  // Ask for the answer uncompressed, so it streams through as it arrives.
  headers['accept-encoding'] = 'identity';

  const label = `${service}${path}`;
  const hasBody = req.method !== 'GET' && req.method !== 'HEAD' && req.body !== null;
  let res;
  try {
    res = await undiciFetch(`${upstream.url}${path}`, {
      method: req.method,
      headers,
      // The web stream undici's types name is the same class Node gives route handlers.
      body: hasBody ? (req.body as unknown as UndiciBody) : undefined,
      duplex: 'half',
      signal: req.signal,
      redirect: 'manual',
      dispatcher: agent(),
    });
  } catch (err) {
    if (req.signal.aborted) return new Response(null, { status: 499 });
    console.warn(`ai proxy: ${label}: upstream unreachable: ${describe(err)}`);
    return upstreamFailure('upstream_unreachable');
  }

  if (res.status === 401 || res.status === 403) {
    await res.body?.cancel();
    console.warn(`ai proxy: ${label}: upstream rejected the request (${res.status}); check ${service.toUpperCase()}_UPSTREAM_KEY`);
    return upstreamFailure('upstream_rejected');
  }
  if (!res.ok && !PASSED_THROUGH_ERRORS.has(res.status)) {
    const detail = await res.text().catch(() => '');
    console.warn(`ai proxy: ${label}: upstream answered ${res.status}: ${detail.slice(0, 500)}`);
    return upstreamFailure('upstream_error');
  }

  const out = new Headers({
    // Next's gzip would buffer a stream of server-sent events.
    'cache-control': 'no-store, no-transform',
    'x-accel-buffering': 'no',
  });
  const type = res.headers.get('content-type');
  if (type) out.set('content-type', type);
  return new Response(res.body as ReadableStream<Uint8Array> | null, { status: res.status, headers: out });
}

/** A 502 a device can't mistake for its own key being refused. */
function upstreamFailure(code: 'upstream_unreachable' | 'upstream_rejected' | 'upstream_error'): Response {
  return Response.json({ error: code }, { status: 502, headers: { 'cache-control': 'no-store' } });
}

function describe(err: unknown): string {
  if (!(err instanceof Error)) return String(err);
  const cause = err.cause instanceof Error ? `: ${err.cause.message}` : '';
  return `${err.message}${cause}`;
}

/** Drop the shared connection pool, with an optional shorter timeout (tests only). */
export async function resetAiProxy(options: { timeoutMs?: number } = {}) {
  const old = dispatcher;
  dispatcher = undefined;
  timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  await old?.close();
}
