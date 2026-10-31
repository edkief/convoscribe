import { isSyncApiKey, newSessionValue, sessionSetCookie } from '@/lib/auth';
import { env } from '@/lib/env';
import { handle, HttpError, readBody, sameOrigin } from '@/lib/http';

const seeOther = (location: string, headers: Record<string, string> = {}) =>
  new Response(null, { status: 303, headers: { Location: location, ...headers } });

/**
 * Sign a browser in with SYNC_API_KEY, from the sign-in page's form (field
 * `key`), in AUTH_MODE=api-key. Sets the sign-in cookie and goes to the
 * library; a wrong key goes back to the form. Behind authz-proxy the proxy
 * signs browsers in, so this is 404.
 */
export async function POST(req: Request) {
  return handle(async () => {
    const config = env();
    if (config.AUTH_MODE !== 'api-key') throw new HttpError(404, 'not_found');
    if (!sameOrigin(req)) throw new HttpError(403, 'cross_site');
    const form = new URLSearchParams(Buffer.from(await readBody(req, 4096)).toString('utf8'));
    const key = form.get('key')?.trim() ?? '';
    if (!key || !isSyncApiKey(key)) return seeOther('/sign-in?error=1');
    return seeOther('/', { 'Set-Cookie': sessionSetCookie(req, newSessionValue()) });
  });
}
