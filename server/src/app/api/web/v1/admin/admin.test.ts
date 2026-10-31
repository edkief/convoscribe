import { createAuthzDevServer, type AuthzDevServer } from '@authz-proxy/sdk/dev';
import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { cleanupRuns } from '@/db/schema';
import { db } from '@/lib/db';
import { noteActivity } from '@/lib/owners';
import { stubServerEnv } from '@/test/env';
import { request, setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { GET as cleanupGet, POST as cleanupPost } from './cleanup/route';
import { GET as statusGet } from './status/route';
import { GET as usersGet, PUT as usersPut } from './users/route';

setupSyncServer();

const JSON_ORIGIN = { origin: 'http://server', 'content-type': 'application/json' };
const put = (body: unknown, headers: Record<string, string> = JSON_ORIGIN) =>
  usersPut(request('/api/web/v1/admin/users', { method: 'PUT', body: JSON.stringify(body), headers }));
const post = (body: unknown, headers: Record<string, string> = JSON_ORIGIN) =>
  cleanupPost(request('/api/web/v1/admin/cleanup', { method: 'POST', body: JSON.stringify(body), headers }));

type Route = { name: string; call: (headers: Record<string, string>) => Promise<Response> };

/** Every admin route, given a browser's headers. */
const routes: Route[] = [
  { name: 'GET users', call: (h) => usersGet(new Request('http://server/x', { headers: h })) },
  {
    name: 'PUT users',
    call: (h) =>
      usersPut(new Request('http://server/x', { method: 'PUT', headers: { ...JSON_ORIGIN, ...h }, body: '{"owner":"alice@example.com","quotaBytes":1}' })),
  },
  { name: 'GET status', call: (h) => statusGet(new Request('http://server/x', { headers: h })) },
  { name: 'GET cleanup', call: (h) => cleanupGet(new Request('http://server/x', { headers: h })) },
  {
    name: 'POST cleanup',
    call: (h) => cleanupPost(new Request('http://server/x', { method: 'POST', headers: { ...JSON_ORIGIN, ...h }, body: '{"dryRun":true}' })),
  },
];

describe('who may use the admin API (#82)', () => {
  let proxy: AuthzDevServer;
  let proxyUrl: string;

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
  });

  afterAll(() => proxy.close());

  const proxyJwt = async (username: string) => {
    await fetch(`${proxyUrl}/__authz/dev/active`, {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ username }),
    });
    const { token } = (await (await fetch(`${proxyUrl}/__authz/dev/token`)).json()) as { token: string };
    return { 'x-auth-request-jwt': token };
  };

  describe('AUTH_MODE=authz-proxy', () => {
    beforeEach(() => stubServerEnv({ AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: proxyUrl, SYNC_API_KEY: '' }));

    it.each(routes)('$name refuses a user who is not an admin', async ({ call }) => {
      await noteActivity('alice@example.com');
      const res = await call(await proxyJwt('alice@example.com'));
      expect(res.status).toBe(403);
      expect(await res.json()).toEqual({ error: 'forbidden' });
    });

    it.each(routes)('$name lets an admin in', async ({ call }) => {
      await noteActivity('alice@example.com');
      const res = await call(await proxyJwt('root@example.com'));
      expect(res.status).toBeLessThan(300);
    });

    it.each(routes)('$name refuses anyone not signed in', async ({ call }) => {
      expect((await call({})).status).toBe(401);
    });
  });

  describe('AUTH_MODE=api-key', () => {
    it.each(routes)('$name refuses anyone not signed in', async ({ call }) => {
      expect((await call({})).status).toBe(401);
    });
  });
});

describe('admin requests from another site (#82)', () => {
  it('are refused', async () => {
    for (const res of [
      await put({ owner: 'default', quotaBytes: 1 }, { origin: 'http://evil.example', 'content-type': 'application/json' }),
      await post({ dryRun: true }, { origin: 'http://evil.example', 'content-type': 'application/json' }),
      await post({ dryRun: true }, { 'content-type': 'application/json' }),
    ]) {
      expect(res.status).toBe(403);
      expect(await res.json()).toEqual({ error: 'cross_site' });
    }
    expect(await db().select().from(cleanupRuns)).toEqual([]);
  });
});

describe('what admins see (#82)', () => {
  const SECRET_TITLE = 'Private title of Alice';
  const SECRET_LINE = 'Confidential words of Alice';
  const SECRET_SPEAKER = 'Speaker-Carol';

  it('holds no title, transcript or audio of another user', async () => {
    const audio = await storeBlob('alice', 'recording of alice');
    await pushManifest(
      'alice',
      sessionManifest('s1', {
        audio,
        title: SECRET_TITLE,
        tags: ['secret-tag'],
        speakers: [{ index: 0, label: SECRET_SPEAKER }],
        lines: [{ startMs: 0, endMs: 1000, text: SECRET_LINE, speakerIndex: 0, tokens: [] }],
      }),
    );
    await noteActivity('alice');
    await db().insert(cleanupRuns).values({ dryRun: true, trigger: 'manual', finishedAt: new Date(), report: {} });

    const bodies = await Promise.all(
      [
        usersGet(request('/x')),
        statusGet(request('/x')),
        cleanupGet(request('/x')),
      ].map(async (res) => {
        const response = await res;
        expect(response.status).toBe(200);
        return response.text();
      }),
    );
    for (const body of bodies) {
      for (const secret of [SECRET_TITLE, SECRET_LINE, SECRET_SPEAKER, 'secret-tag', audio.sha256, 's1']) {
        expect(body).not.toContain(secret);
      }
    }
    const { users } = JSON.parse(bodies[0]) as { users: Record<string, unknown>[] };
    const alice = users.find((u) => u.owner === 'alice')!;
    expect(Object.keys(alice).sort()).toEqual(['bytes', 'firstSeenAt', 'lastSeenAt', 'owner', 'quotaBytes', 'sessions', 'trashed']);
    expect(alice).toMatchObject({ sessions: 1, trashed: 0 });
  });
});

describe('setting a quota (#82)', () => {
  it('stores it, and null is unlimited', async () => {
    await noteActivity('alice');
    expect((await put({ owner: 'alice', quotaBytes: 5000 })).status).toBe(200);
    const quota = async () =>
      ((await (await usersGet(request('/x'))).json()) as { users: { owner: string; quotaBytes: number | null }[] }).users.find(
        (u) => u.owner === 'alice',
      )!.quotaBytes;
    expect(await quota()).toBe(5000);
    expect((await put({ owner: 'alice', quotaBytes: null })).status).toBe(200);
    expect(await quota()).toBeNull();
  });

  it('refuses a bad body and someone not seen', async () => {
    await noteActivity('alice');
    for (const body of [{ owner: 'alice' }, { owner: 'alice', quotaBytes: -1 }, { owner: 'alice', quotaBytes: 1.5 }, { quotaBytes: 1 }, null]) {
      expect((await put(body)).status).toBe(400);
    }
    expect((await put({ owner: 'nobody', quotaBytes: 1 })).status).toBe(404);
  });
});

describe('clean-up from the admin API (#82)', () => {
  it('starts a preview in the background, refusing a second while it runs', async () => {
    await db().insert(cleanupRuns).values({ dryRun: false, trigger: 'scheduled' });
    expect((await post({ dryRun: false })).status).toBe(409);
    const res = await post({ dryRun: true });
    expect(res.status).toBe(202);
    expect(await res.json()).toEqual({ id: expect.any(Number) });
    await expect.poll(async () => ((await (await cleanupGet(request('/x'))).json()) as { preview: { last: unknown } }).preview.last).not.toBeNull();
  });

  it('refuses a bad body', async () => {
    expect((await post({ dryRun: 'yes' })).status).toBe(400);
    expect((await post(null)).status).toBe(400);
  });
});

describe('status from the admin API (#82)', () => {
  it('reports both reachable and the storage figures', async () => {
    const body = (await (await statusGet(request('/x'))).json()) as Record<string, unknown>;
    expect(body).toMatchObject({ postgres: { ok: true }, s3: { ok: true }, storage: { total: 0 } });
    expect(JSON.stringify(body)).not.toMatch(/secret|S3_|bucket/i);
  });
});
