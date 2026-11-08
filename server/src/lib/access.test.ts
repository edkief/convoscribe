import { describe, expect, it } from 'vitest';
import { GET as changes } from '@/app/api/sync/v1/changes/route';
import { GET as whoami } from '@/app/api/sync/v1/whoami/route';
import { GET as lists } from '@/app/api/web/v1/talking-points/route';
import { stubServerEnv } from '@/test/env';
import { request, setupSyncServer } from '@/test/http';
import { newSessionValue, SESSION_COOKIE } from './auth';
import { listOwners, noteActivity } from './owners';
import { assertService, servicesOf, setServices } from './access';

setupSyncServer();

const all = ['sync', 'llm', 'asr', 'decision'];

describe('what a user may use (#145)', () => {
  it('is the default for someone not seen yet, or not given any', async () => {
    expect([...(await servicesOf('alice'))]).toEqual(all);
    stubServerEnv({ DEFAULT_USER_SERVICES: 'sync' });
    await noteActivity('alice');
    expect([...(await servicesOf('alice'))]).toEqual(['sync']);
    expect([...(await servicesOf('bob'))]).toEqual(['sync']);
  });

  it('is what an admin gave them, until set back to the default', async () => {
    await noteActivity('alice');
    await setServices('alice', ['decision', 'llm', 'llm']);
    expect([...(await servicesOf('alice'))]).toEqual(['llm', 'decision']);
    expect((await listOwners())[0].services).toEqual(['llm', 'decision']);
    await setServices('alice', []);
    expect(await servicesOf('alice')).toEqual(new Set());
    await setServices('alice', null);
    expect([...(await servicesOf('alice'))]).toEqual(all);
    expect((await listOwners())[0].services).toBeNull();
  });

  it('can only be set for someone this server has seen', async () => {
    await expect(setServices('nobody', ['sync'])).rejects.toMatchObject({ status: 404, code: 'unknown_owner' });
  });

  it('refuses a service the user was not given', async () => {
    await noteActivity('alice');
    await setServices('alice', ['llm']);
    await expect(assertService('alice', 'llm')).resolves.toBeUndefined();
    await expect(assertService('alice', 'sync')).rejects.toMatchObject({ status: 403, code: 'service_not_allowed' });
  });
});

describe('sync and the web library need sync (#145)', () => {
  const browser = () => ({ authorization: '', cookie: `${SESSION_COOKIE}=${newSessionValue()}` });

  it('refuses a device and a browser without it, but still says who they are', async () => {
    await noteActivity('default');
    await setServices('default', ['llm']);
    const device = await changes(request('/api/sync/v1/changes?since=0'));
    expect(device.status).toBe(403);
    expect(await device.json()).toEqual({ error: 'service_not_allowed', service: 'sync' });
    expect((await lists(request('/api/web/v1/talking-points', { headers: browser() }))).status).toBe(403);
    expect((await whoami(request('/api/sync/v1/whoami'))).status).toBe(200);
  });

  it('lets them through with it', async () => {
    expect((await changes(request('/api/sync/v1/changes?since=0'))).status).toBe(200);
    expect((await lists(request('/api/web/v1/talking-points', { headers: browser() }))).status).toBe(200);
  });

  it('answers 401 before saying a service is not given', async () => {
    stubServerEnv({ DEFAULT_USER_SERVICES: 'none' });
    expect((await changes(request('/api/sync/v1/changes?since=0', { headers: { authorization: 'Bearer wrong' } }))).status).toBe(401);
  });
});
