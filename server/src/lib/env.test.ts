import { describe, expect, it } from 'vitest';
import { loadEnv } from './env';

const base = {
  DATABASE_URL: 'postgres://u:p@localhost:5432/db',
  S3_BUCKET: 'bucket',
  S3_ACCESS_KEY_ID: 'id',
  S3_SECRET_ACCESS_KEY: 'secret',
};
const apiKey = 'k'.repeat(32);

describe('loadEnv', () => {
  it('accepts api-key mode and applies defaults', () => {
    const env = loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey });
    expect(env.S3_REGION).toBe('us-east-1');
    expect(env.S3_FORCE_PATH_STYLE).toBe(true);
    expect(env.S3_ENDPOINT).toBeUndefined();
  });

  it('requires SYNC_API_KEY in api-key mode', () => {
    expect(() => loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: '' })).toThrow(/SYNC_API_KEY/);
  });

  it('rejects a short SYNC_API_KEY', () => {
    expect(() => loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: 'short' })).toThrow(/SYNC_API_KEY/);
  });

  it('requires AUTHZ_PROXY_URL in authz-proxy mode', () => {
    expect(() => loadEnv({ ...base, AUTH_MODE: 'authz-proxy' })).toThrow(/AUTHZ_PROXY_URL/);
    expect(loadEnv({ ...base, AUTH_MODE: 'authz-proxy', AUTHZ_PROXY_URL: 'http://proxy' }).AUTHZ_PROXY_URL).toBe(
      'http://proxy',
    );
  });

  it('lists every missing variable', () => {
    expect(() => loadEnv({ AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey })).toThrow(/DATABASE_URL[\s\S]*S3_BUCKET/);
  });

  it('parses S3_FORCE_PATH_STYLE=false', () => {
    const env = loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey, S3_FORCE_PATH_STYLE: 'false' });
    expect(env.S3_FORCE_PATH_STYLE).toBe(false);
  });

  it('splits ADMIN_ROLES and treats blank as the SDK default', () => {
    const roles = (value: string) =>
      loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey, ADMIN_ROLES: value }).ADMIN_ROLES;
    expect(roles('ops, convoscribe-admin ,')).toEqual(['ops', 'convoscribe-admin']);
    expect(roles('')).toBeUndefined();
  });
});
