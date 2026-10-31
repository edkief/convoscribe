import { createAuthzDevServer, type AuthzDevServer } from '@authz-proxy/sdk/dev';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { stubServerEnv, TEST_API_KEY } from '@/test/env';
import {
  DEFAULT_OWNER,
  newSessionValue,
  resolveIdentity,
  resolveOwner,
  SESSION_COOKIE,
  SESSION_MAX_AGE_S,
  sessionSetCookie,
  verifySessionValue,
} from './auth';

const request = (authorization?: string) =>
  new Request('http://server/api/sync/v1/whoami', authorization ? { headers: { authorization } } : {});

const browser = (headers: Record<string, string>) => new Request('http://server/', { headers });

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('resolveOwner, AUTH_MODE=api-key', () => {
  it('resolves the default owner for the configured key', async () => {
    stubServerEnv();
    expect(await resolveOwner(request(`Bearer ${TEST_API_KEY}`))).toBe(DEFAULT_OWNER);
    expect(await resolveOwner(request(`bearer ${TEST_API_KEY}`))).toBe(DEFAULT_OWNER);
  });

  it('rejects a wrong, missing or malformed key', async () => {
    stubServerEnv();
    expect(await resolveOwner(request(`Bearer ${TEST_API_KEY}x`))).toBeNull();
    expect(await resolveOwner(request('Bearer short'))).toBeNull();
    expect(await resolveOwner(request())).toBeNull();
    expect(await resolveOwner(request(TEST_API_KEY))).toBeNull();
    expect(await resolveOwner(request(`Basic ${TEST_API_KEY}`))).toBeNull();
  });
});

describe('web sign-in, AUTH_MODE=api-key', () => {
  it('resolves the owner, an admin, from the key or a sign-in cookie', async () => {
    stubServerEnv();
    const admin = { owner: DEFAULT_OWNER, isAdmin: true };
    expect(await resolveIdentity(request(`Bearer ${TEST_API_KEY}`))).toEqual(admin);
    expect(await resolveIdentity(browser({ cookie: `a=b; ${SESSION_COOKIE}=${newSessionValue()}` }))).toEqual(admin);
    expect(await resolveIdentity(browser({}))).toBeNull();
  });

  it('never lets the cookie authenticate the sync API', async () => {
    stubServerEnv();
    expect(await resolveOwner(browser({ cookie: `${SESSION_COOKIE}=${newSessionValue()}` }))).toBeNull();
  });

  it('refuses a tampered, expired, future or other-key cookie', () => {
    stubServerEnv();
    const now = Date.now();
    const value = newSessionValue(now);
    expect(verifySessionValue(value, now)).toBe(true);
    expect(verifySessionValue(value.replace(/.$/, (c) => (c === '0' ? '1' : '0')), now)).toBe(false);
    expect(verifySessionValue(value, now + SESSION_MAX_AGE_S * 1000 + 1)).toBe(false);
    expect(verifySessionValue(newSessionValue(now + 3_600_000), now)).toBe(false);
    expect(verifySessionValue(TEST_API_KEY, now)).toBe(false);
    stubServerEnv({ SYNC_API_KEY: 'another-key-0123456789abcdef0123456789' });
    expect(verifySessionValue(value, now)).toBe(false);
  });

  it('holds no key in the cookie, and sets it HttpOnly, SameSite=Strict, Secure over https', () => {
    stubServerEnv();
    expect(newSessionValue()).not.toContain(TEST_API_KEY);
    const plain = sessionSetCookie(browser({}), 'v');
    expect(plain).toMatch(/HttpOnly; SameSite=Strict; Max-Age=2592000$/);
    expect(sessionSetCookie(browser({ 'x-forwarded-proto': 'https' }), 'v')).toMatch(/; Secure$/);
    expect(sessionSetCookie(browser({}), null)).toMatch(new RegExp(`^${SESSION_COOKIE}=; .*Max-Age=0`));
  });
});

describe('resolveOwner, AUTH_MODE=authz-proxy', () => {
  let proxy: AuthzDevServer;
  let proxyUrl: string;
  // Stands in for the proxy rejecting a revoked or unknown key.
  let rejectingProxy: Server;
  let rejectingProxyUrl: string;

  beforeAll(async () => {
    proxy = await createAuthzDevServer(
      {
        users: [
          { username: 'alice@example.com', roles: ['convoscribe'] },
          { username: 'root@example.com', roles: ['convoscribe', 'role-authz-admin'] },
        ],
      },
      { port: 0, host: '127.0.0.1', target: null, ui: false },
    );
    proxyUrl = (await proxy.listen()).url;

    rejectingProxy = createServer((_req, res) => res.writeHead(401).end());
    await new Promise<void>((resolve) => rejectingProxy.listen(0, '127.0.0.1', resolve));
    rejectingProxyUrl = `http://127.0.0.1:${(rejectingProxy.address() as AddressInfo).port}`;
  });

  afterAll(async () => {
    await proxy.close();
    await new Promise((resolve) => rejectingProxy.close(resolve));
  });

  it('resolves the owner the proxy reports for an sk_authz_ key', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' });
    expect(await resolveOwner(request('Bearer sk_authz_abc_secret'))).toBe('alice@example.com');
  });

  it('rejects a key the proxy refuses', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: rejectingProxyUrl, SYNC_API_KEY: '' });
    expect(await resolveOwner(request('Bearer sk_authz_abc_revoked'))).toBeNull();
  });

  it('rejects a request without a key', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' });
    expect(await resolveOwner(request())).toBeNull();
  });

  // What the proxy adds to a browser request it let through (#80).
  const proxyJwt = async (username: string) => {
    await fetch(`${proxyUrl}/__authz/dev/active`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username }),
    });
    const { token } = (await (await fetch(`${proxyUrl}/__authz/dev/token`)).json()) as { token: string };
    return token;
  };

  it('resolves a browser user from the proxy JWT, an admin only with an admin role', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' });
    const alice = browser({ 'x-auth-request-jwt': await proxyJwt('alice@example.com') });
    expect(await resolveIdentity(alice)).toEqual({ owner: 'alice@example.com', isAdmin: false });
    expect(await resolveOwner(alice)).toBe('alice@example.com');
    const root = browser({ 'x-auth-request-jwt': await proxyJwt('root@example.com') });
    expect(await resolveIdentity(root)).toEqual({ owner: 'root@example.com', isAdmin: true });
  });

  it('takes admin roles from ADMIN_ROLES', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '', ADMIN_ROLES: 'convoscribe' });
    const alice = browser({ 'x-auth-request-jwt': await proxyJwt('alice@example.com') });
    expect(await resolveIdentity(alice)).toEqual({ owner: 'alice@example.com', isAdmin: true });
  });

  it('refuses a forged JWT and ignores the api-key sign-in cookie', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' });
    const real = await proxyJwt('alice@example.com');
    const [header, , signature] = real.split('.');
    const payload = Buffer.from(JSON.stringify({ sub: 'root@example.com', email: 'root@example.com' })).toString(
      'base64url',
    );
    expect(await resolveIdentity(browser({ 'x-auth-request-jwt': `${header}.${payload}.${signature}` }))).toBeNull();
    expect(await resolveIdentity(browser({ cookie: `${SESSION_COOKIE}=1.${'0'.repeat(64)}` }))).toBeNull();
  });

  it('ignores SYNC_API_KEY', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: rejectingProxyUrl });
    expect(await resolveOwner(request(`Bearer ${TEST_API_KEY}`))).toBeNull();
  });
});
