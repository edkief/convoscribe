import { createAuthzMiddleware, type AuthzMiddleware } from '@authz-proxy/sdk/next/middleware';
import { createHash, timingSafeEqual } from 'node:crypto';
import { env } from './env';

/** The single owner in AUTH_MODE=api-key. */
export const DEFAULT_OWNER = 'default';

function bearerToken(req: Request): string | undefined {
  const match = /^Bearer\s+(\S+)\s*$/i.exec(req.headers.get('authorization') ?? '');
  return match?.[1];
}

/** Constant-time comparison; hashing first hides the length of the expected key. */
function sameKey(a: string, b: string): boolean {
  const digest = (value: string) => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(a), digest(b));
}

let authzProxy: AuthzMiddleware | undefined;

async function ownerFromAuthzProxy(req: Request, proxyBaseUrl: string): Promise<string | null> {
  authzProxy ??= createAuthzMiddleware({ proxyBaseUrl });
  // Devices send an sk_authz_ key, which the SDK resolves through the proxy's
  // userinfo endpoint. There is no browser, so no session cookie to read.
  const session = await authzProxy({ headers: req.headers, cookies: { get: () => undefined }, url: req.url });
  return session.isAuthenticated ? (session.user?.username ?? null) : null;
}

/**
 * The owner a request acts for, from its `Authorization: Bearer <key>` header,
 * or null when the request is not authenticated.
 */
export async function resolveOwner(req: Request): Promise<string | null> {
  const config = env();
  if (config.AUTH_MODE === 'authz-proxy') {
    return ownerFromAuthzProxy(req, config.AUTHZ_PROXY_URL!);
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
