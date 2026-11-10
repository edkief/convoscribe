import { describe, expect, it } from 'vitest';
import { stubServerEnv } from '@/test/env';
import { GET } from './route';

const proxyMode = { AUTH_MODE: 'authz-proxy', SYNC_API_KEY: '', AUTHZ_PROXY_URL: 'http://authz.internal:3000/' };

describe('GET /api/public/v1/sign-in (#95)', () => {
  it('offers no sign-in in api-key mode', async () => {
    stubServerEnv();
    const res = GET();
    expect(res.status).toBe(200);
    expect(res.headers.get('cache-control')).toBe('no-store');
    expect(await res.json()).toEqual({ authzProxy: null });
  });

  it('offers no sign-in without a client id', async () => {
    stubServerEnv(proxyMode);
    expect(await GET().json()).toEqual({ authzProxy: null });
  });

  it('names the browser-facing proxy, the client and the roles', async () => {
    stubServerEnv({
      ...proxyMode,
      DEVICE_SIGN_IN_CLIENT_ID: 'convoscribe',
      DEVICE_SIGN_IN_PROXY_URL: 'https://auth.example.com',
      DEVICE_KEY_ROLES: 'convoscribe',
    });
    expect(await GET().json()).toEqual({
      authzProxy: { url: 'https://auth.example.com', clientId: 'convoscribe', roles: ['convoscribe'] },
    });
  });

  it('falls back to AUTHZ_PROXY_URL and asks for every role', async () => {
    stubServerEnv({ ...proxyMode, DEVICE_SIGN_IN_CLIENT_ID: 'convoscribe' });
    expect(await GET().json()).toEqual({
      authzProxy: { url: 'http://authz.internal:3000', clientId: 'convoscribe', roles: null },
    });
  });
});
