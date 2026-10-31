import type { Identity } from './auth';

/** An error a route answers with as `{ error, ...details }` and `status`. */
export class HttpError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    readonly details: Record<string, unknown> = {},
  ) {
    super(code);
  }

  toResponse(): Response {
    return Response.json({ error: this.code, ...this.details }, { status: this.status });
  }
}

/** Run `handler`, turning an [HttpError] into its response. */
export async function handle(handler: () => Promise<Response>): Promise<Response> {
  try {
    return await handler();
  } catch (err) {
    if (err instanceof HttpError) return err.toResponse();
    throw err;
  }
}

const SHA256 = /^[0-9a-f]{64}$/;

/** A lower-case hex SHA-256, or a 400. */
export function sha256Param(value: string): string {
  if (!SHA256.test(value)) throw new HttpError(400, 'invalid_sha256');
  return value;
}

/**
 * Document kinds the server accepts: sessions (#68), and known voices,
 * presets, summary templates and a session's semantic vectors (#70).
 */
export const KINDS = ['session', 'voice', 'preset', 'template', 'vectors'] as const;
export type Kind = (typeof KINDS)[number];

export function kindParam(value: string): Kind {
  if (!(KINDS as readonly string[]).includes(value)) throw new HttpError(404, 'unknown_kind');
  return value as Kind;
}

const SYNC_ID = /^[0-9A-Za-z-]{1,64}$/;

export function syncIdParam(value: string): string {
  if (!SYNC_ID.test(value)) throw new HttpError(400, 'invalid_sync_id');
  return value;
}

/** A request body read whole, refusing anything over `limit` bytes with a 413. */
export async function readBody(req: Request, limit: number): Promise<Uint8Array> {
  const declared = Number(req.headers.get('content-length') ?? NaN);
  if (declared > limit) throw new HttpError(413, 'too_large', { limit });
  if (!req.body) return new Uint8Array();
  const chunks: Uint8Array[] = [];
  let total = 0;
  const reader = req.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.length;
    if (total > limit) {
      await reader.cancel();
      throw new HttpError(413, 'too_large', { limit });
    }
    chunks.push(value);
  }
  return Buffer.concat(chunks);
}

/** Parse an HTTP `Range: bytes=a-b` header against an object of `size` bytes. */
export function parseRange(header: string | null, size: number): { start: number; end: number } | undefined {
  if (!header) return undefined;
  const match = /^bytes=(\d*)-(\d*)$/.exec(header.trim());
  if (!match || (match[1] === '' && match[2] === '')) throw new HttpError(416, 'invalid_range');
  let start: number;
  let end: number;
  if (match[1] === '') {
    start = Math.max(0, size - Number(match[2]));
    end = size - 1;
  } else {
    start = Number(match[1]);
    end = match[2] === '' ? size - 1 : Math.min(Number(match[2]), size - 1);
  }
  if (start > end || start >= size) throw new HttpError(416, 'invalid_range', { size });
  return { start, end };
}

/** Resolve the caller's owner, answer 401 without one, and run `handler` with HttpErrors mapped. */
export async function authed(req: Request, handler: (owner: string) => Promise<Response>): Promise<Response> {
  const { resolveOwner, unauthorized } = await import('./auth');
  const owner = await resolveOwner(req);
  if (!owner) return unauthorized();
  return handle(() => handler(owner));
}

/**
 * Whether a browser request comes from a page of this server: its `Origin`
 * names the host the request was sent to (`X-Forwarded-Host` behind the
 * reverse proxy). Browsers send `Origin` on every state-changing request,
 * so a missing one is refused too (#79, decision 2).
 */
export function sameOrigin(req: Request): boolean {
  const origin = req.headers.get('origin');
  if (!origin) return false;
  let host: string;
  try {
    host = new URL(origin).host;
  } catch {
    return false;
  }
  const target = req.headers.get('x-forwarded-host') ?? req.headers.get('host') ?? new URL(req.url).host;
  return host.toLowerCase() === target.split(',')[0].trim().toLowerCase();
}

const SAFE_METHODS = new Set(['GET', 'HEAD']);

/**
 * For the web app's routes (#80): refuse a state-changing request from
 * another site with `403 cross_site`, resolve the browser's identity (401
 * without one), and run `handler` with HttpErrors mapped.
 */
export async function webAuthed(req: Request, handler: (identity: Identity) => Promise<Response>): Promise<Response> {
  if (!SAFE_METHODS.has(req.method) && !sameOrigin(req)) return new HttpError(403, 'cross_site').toResponse();
  const { resolveIdentity } = await import('./auth');
  const identity = await resolveIdentity(req);
  if (!identity) return new HttpError(401, 'unauthorized').toResponse();
  return handle(() => handler(identity));
}

/** A required non-negative integer header, or a 400 naming it. */
export function intHeader(req: Request, name: string): number {
  const raw = req.headers.get(name);
  const value = raw === null || raw.trim() === '' ? NaN : Number(raw);
  if (!Number.isSafeInteger(value) || value < 0) throw new HttpError(400, 'invalid_header', { header: name });
  return value;
}
