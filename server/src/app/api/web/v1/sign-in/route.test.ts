import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveIdentity } from '@/lib/auth';
import { stubServerEnv, TEST_API_KEY } from '@/test/env';
import { POST as signOut } from '../sign-out/route';
import { POST as signIn } from './route';

const form = (key: string, headers: Record<string, string> = { origin: 'http://server' }) =>
  new Request('http://server/api/web/v1/sign-in', {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded', ...headers },
    body: new URLSearchParams({ key }).toString(),
  });

const cookieOf = (res: Response) => res.headers.get('set-cookie')?.split(';')[0] ?? '';

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('POST /api/web/v1/sign-in', () => {
  it('sets a sign-in cookie for SYNC_API_KEY and goes to the library', async () => {
    stubServerEnv();
    const res = await signIn(form(TEST_API_KEY));
    expect(res.status).toBe(303);
    expect(res.headers.get('location')).toBe('/');
    expect(res.headers.get('set-cookie')).toMatch(/HttpOnly; SameSite=Strict/);
    const identity = await resolveIdentity(new Request('http://server/', { headers: { cookie: cookieOf(res) } }));
    expect(identity).toEqual({ owner: 'default', isAdmin: true });
  });

  it('sends a wrong key back to the form, with no cookie', async () => {
    stubServerEnv();
    for (const key of [`${TEST_API_KEY}x`, '']) {
      const res = await signIn(form(key));
      expect(res.status).toBe(303);
      expect(res.headers.get('location')).toBe('/sign-in?error=1');
      expect(res.headers.get('set-cookie')).toBeNull();
    }
  });

  it('refuses a sign-in posted from another site or without an Origin', async () => {
    stubServerEnv();
    for (const headers of [{ origin: 'https://evil.example' }, {}] as Record<string, string>[]) {
      const res = await signIn(form(TEST_API_KEY, headers));
      expect(res.status).toBe(403);
      expect(res.headers.get('set-cookie')).toBeNull();
    }
  });

  it('is not there behind authz-proxy', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: 'http://proxy', SYNC_API_KEY: '' });
    expect((await signIn(form(TEST_API_KEY))).status).toBe(404);
  });
});

describe('POST /api/web/v1/sign-out', () => {
  const out = (headers: Record<string, string>) =>
    signOut(new Request('http://server/api/web/v1/sign-out', { method: 'POST', headers }));

  it('clears the cookie and goes to the sign-in page', async () => {
    stubServerEnv();
    const res = await out({ origin: 'http://server' });
    expect(res.status).toBe(303);
    expect(res.headers.get('location')).toBe('/sign-in');
    expect(res.headers.get('set-cookie')).toMatch(/^convoscribe_session=; .*Max-Age=0/);
  });

  it('refuses a sign-out posted from another site', async () => {
    stubServerEnv();
    expect((await out({ origin: 'https://evil.example' })).status).toBe(403);
  });

  it("goes to the proxy's sign-out behind authz-proxy", async () => {
    stubServerEnv({
      AUTH_MODE: 'authz-proxy',
      AUTHZ_PROXY_URL: 'http://proxy',
      SYNC_API_KEY: '',
      WEB_SIGN_OUT_URL: 'https://auth.example.com/sign-out',
    });
    const res = await out({ origin: 'http://server' });
    expect(res.headers.get('location')).toBe('https://auth.example.com/sign-out');
    expect(res.headers.get('set-cookie')).toBeNull();
  });
});
