import { env } from '@/lib/env';

/**
 * How a device signs in (#95), asked before it holds any key, so this route
 * takes no auth and the reverse proxy must let it through (a ForwardAuth
 * bypass for /api/public/). With DEVICE_SIGN_IN_CLIENT_ID set, it names the
 * authz-proxy a browser reaches, the public client the app starts an API key
 * request as, and the roles to ask for (null: all of the user's). Otherwise
 * `authzProxy` is null and the device pastes a key. Nothing here is secret.
 */
export function GET() {
  const config = env();
  const clientId = config.AUTH_MODE === 'authz-proxy' ? config.DEVICE_SIGN_IN_CLIENT_ID : undefined;
  const url = config.DEVICE_SIGN_IN_PROXY_URL ?? config.AUTHZ_PROXY_URL?.replace(/\/+$/, '');
  const authzProxy = clientId && url ? { url, clientId, roles: config.DEVICE_KEY_ROLES ?? null } : null;
  return Response.json({ authzProxy }, { headers: { 'cache-control': 'no-store' } });
}
