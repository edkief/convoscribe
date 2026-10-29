import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubServerEnv, TEST_API_KEY } from '@/test/env';
import { GET } from './route';

const whoami = (authorization?: string) =>
  GET(new Request('http://server/api/sync/v1/whoami', authorization ? { headers: { authorization } } : {}));

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('GET /api/sync/v1/whoami', () => {
  it('returns the owner for a valid key', async () => {
    stubServerEnv();
    const res = await whoami(`Bearer ${TEST_API_KEY}`);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ owner: 'default' });
  });

  it('returns 401 with a Bearer challenge otherwise', async () => {
    stubServerEnv();
    for (const res of [await whoami(), await whoami('Bearer nope')]) {
      expect(res.status).toBe(401);
      expect(res.headers.get('WWW-Authenticate')).toMatch(/^Bearer /);
    }
  });
});
