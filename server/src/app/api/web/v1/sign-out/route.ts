import { sessionSetCookie } from '@/lib/auth';
import { env } from '@/lib/env';
import { handle, HttpError, sameOrigin } from '@/lib/http';

/**
 * Sign this browser out: clear the sign-in cookie and go to the sign-in
 * page. Behind authz-proxy the session is the proxy's, so this goes to
 * WEB_SIGN_OUT_URL, or to the library when it is not set.
 */
export async function POST(req: Request) {
  return handle(async () => {
    if (!sameOrigin(req)) throw new HttpError(403, 'cross_site');
    const config = env();
    if (config.AUTH_MODE === 'authz-proxy') {
      return new Response(null, { status: 303, headers: { Location: config.WEB_SIGN_OUT_URL ?? '/' } });
    }
    return new Response(null, {
      status: 303,
      headers: { Location: '/sign-in', 'Set-Cookie': sessionSetCookie(req, null) },
    });
  });
}
