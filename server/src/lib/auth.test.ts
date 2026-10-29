import { createAuthzDevServer, type AuthzDevServer } from '@authz-proxy/sdk/dev';
import { createServer, type Server } from 'node:http';
import type { AddressInfo } from 'node:net';
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest';
import { stubServerEnv, TEST_API_KEY } from '@/test/env';
import { DEFAULT_OWNER, resolveOwner } from './auth';

const request = (authorization?: string) =>
  new Request('http://server/api/sync/v1/whoami', authorization ? { headers: { authorization } } : {});

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

describe('resolveOwner, AUTH_MODE=authz-proxy', () => {
  let proxy: AuthzDevServer;
  let proxyUrl: string;
  // Stands in for the proxy rejecting a revoked or unknown key.
  let rejectingProxy: Server;
  let rejectingProxyUrl: string;

  beforeAll(async () => {
    proxy = await createAuthzDevServer(
      { users: [{ username: 'alice@example.com', roles: ['convoscribe'] }] },
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

  it('ignores SYNC_API_KEY', async () => {
    stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: rejectingProxyUrl });
    expect(await resolveOwner(request(`Bearer ${TEST_API_KEY}`))).toBeNull();
  });
});
