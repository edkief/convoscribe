import { createAuthzMiddleware, type AuthzMiddleware } from '@authz-proxy/sdk/next/middleware';
import { createHash, createHmac, timingSafeEqual } from 'node:crypto';
import { env } from './env';

/** The single owner in AUTH_MODE=api-key. */
export const DEFAULT_OWNER = 'default';

function bearerToken(req: { headers: Headers }): string | undefined {
  const match = /^Bearer\s+(\S+)\s*$/i.exec(req.headers.get('authorization') ?? '');
  return match?.[1];
}

/** Constant-time comparison; hashing first hides the length of the expected key. */
function sameKey(a: string, b: string): boolean {
  const digest = (value: string) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(a), digest(b));
}

/** Who a request acts for (#79, decision 1). */
export type Identity = { owner: string; isAdmin: boolean };

/** What identity is read from: a route's `Request`, or a page's headers. */
export type RequestLike = { headers: Headers; url: string };

let authzProxy: AuthzMiddleware | undefined;

async function identityFromAuthzProxy(req: RequestLike, proxyBaseUrl: string): Promise<Identity | null> {
  authzProxy ??= createAuthzMiddleware({ proxyBaseUrl, adminRoles: env().ADMIN_ROLES });
  // Devices send an sk_authz_ key, which the SDK resolves through the proxy's
  // userinfo endpoint. Browsers come through the proxy, which adds a signed
  // X-Auth-Request-JWT the SDK verifies against the proxy's JWKS (#80). The
  // proxy's own session cookie is not read here.
  const session = await authzProxy({ headers: req.headers, cookies: { get: () => undefined }, url: req.url });
  const owner = session.isAuthenticated ? session.user?.username : undefined;
  return owner ? { owner, isAdmin: session.isAdmin } : null;
}

/** Whether `key` is SYNC_API_KEY (AUTH_MODE=api-key), compared in constant time. */
export function isSyncApiKey(key: string): boolean {
  const expected = env().SYNC_API_KEY;
  return expected !== undefined && sameKey(key, expected);
}

/** The web sign-in cookie in AUTH_MODE=api-key (#80). */
export const SESSION_COOKIE = 'convoscribe_session';

/** How long a web sign-in lasts. */
export const SESSION_MAX_AGE_S = 30 * 24 * 60 * 60;

const sessionMac = (issuedAt: number) =>
  createHmac('sha256', env().SYNC_API_KEY!).update(`web-session:${issuedAt}`).digest('hex');

/**
 * A new sign-in cookie value, `<issuedAtMs>.<HMAC of it keyed by
 * SYNC_API_KEY>`: it never holds the key, and changing the key signs every
 * browser out.
 */
export function newSessionValue(now: number = Date.now()): string {
  return `${now}.${sessionMac(now)}`;
}

/** Whether `value` is a sign-in cookie this server issued less than [SESSION_MAX_AGE_S] ago. */
export function verifySessionValue(value: string | undefined, now: number = Date.now()): boolean {
  if (!value || env().AUTH_MODE !== 'api-key') return false;
  const match = /^(\d{1,15})\.([0-9a-f]{64})$/.exec(value);
  if (!match) return false;
  const issuedAt = Number(match[1]);
  if (issuedAt > now + 60_000 || now - issuedAt > SESSION_MAX_AGE_S * 1000) return false;
  return sameKey(match[2], sessionMac(issuedAt));
}

/** The value of cookie `name` in a request's `Cookie` header. */
export function cookieValue(headers: Headers, name: string): string | undefined {
  for (const part of (headers.get('cookie') ?? '').split(';')) {
    const eq = part.indexOf('=');
    if (eq > 0 && part.slice(0, eq).trim() === name) return decodeURIComponent(part.slice(eq + 1).trim());
  }
  return undefined;
}

/** A `Set-Cookie` header setting the sign-in cookie to `value`, or clearing it when null. */
export function sessionSetCookie(req: RequestLike, value: string | null): string {
  const proto = req.headers.get('x-forwarded-proto')?.split(',')[0].trim() ?? new URL(req.url).protocol.slice(0, -1);
  return [
    `${SESSION_COOKIE}=${value ?? ''}`,
    'Path=/',
    'HttpOnly',
    'SameSite=Strict',
    `Max-Age=${value ? SESSION_MAX_AGE_S : 0}`,
    ...(proto === 'https' ? ['Secure'] : []),
  ].join('; ');
}

/**
 * Who a device or browser request acts for, or null when it is not
 * authenticated. In AUTH_MODE=api-key that is the single owner, an admin,
 * from the Bearer key or the web sign-in cookie; behind authz-proxy, the
 * proxy's user, an admin when they hold one of ADMIN_ROLES.
 */
export async function resolveIdentity(req: RequestLike): Promise<Identity | null> {
  const config = env();
  if (config.AUTH_MODE === 'authz-proxy') {
    return identityFromAuthzProxy(req, config.AUTHZ_PROXY_URL!);
  }
  const token = bearerToken(req);
  const signedIn =
    (token !== undefined && isSyncApiKey(token)) ||
    verifySessionValue(cookieValue(req.headers, SESSION_COOKIE));
  return signedIn ? { owner: DEFAULT_OWNER, isAdmin: true } : null;
}

/**
 * The owner a device request acts for, from its `Authorization: Bearer
 * <key>` header, or null when the request is not authenticated. The web
 * sign-in cookie does not count, so the sync API never acts on a request a
 * browser sent on its own.
 */
export async function resolveOwner(req: Request): Promise<string | null> {
  const config = env();
  if (config.AUTH_MODE === 'authz-proxy') {
    return (await identityFromAuthzProxy(req, config.AUTHZ_PROXY_URL!))?.owner ?? null;
  }
  const token = bearerToken(req);
  return token && sameKey(token, config.SYNC_API_KEY!) ? DEFAULT_OWNER : null;
}

/** The 401 every authenticated endpoint answers with. */
export function unauthorized(): Response {
  return Response.json(
    { error: 'unauthorized' },
    { status: 401, headers: { 'WWW-Authenticate': 'Bearer realm="convoscribe-sync"' } },
  );
}

/** Forget the cached SDK client (tests only). */
export function resetAuth() {
  authzProxy = undefined;
}
