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

  it('runs clean-up by default, and accepts dry-run and off (#81)', () => {
    const cleanup = (value?: string) =>
      loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey, ...(value === undefined ? {} : { CLEANUP: value }) }).CLEANUP;
    expect(cleanup()).toBe('on');
    expect(cleanup('')).toBe('on');
    expect(cleanup('dry-run')).toBe('dry-run');
    expect(cleanup('off')).toBe('off');
    expect(() => cleanup('maybe')).toThrow(/CLEANUP/);
  });

  describe('upstreams (#93)', () => {
    const load = (extra: Record<string, string>) => loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey, ...extra });

    it('starts with none configured', () => {
      const env = load({ LLM_UPSTREAM_URL: '', ASR_UPSTREAM_URL: '' });
      expect(env.LLM_UPSTREAM_URL).toBeUndefined();
      expect(env.ASR_UPSTREAM_URL).toBeUndefined();
    });

    it('accepts an upstream with a key and model, dropping trailing slashes', () => {
      const env = load({ ASR_UPSTREAM_URL: 'http://parakeet-asr.asr:8000/v1/', ASR_UPSTREAM_KEY: 'k', ASR_MODEL: 'parakeet' });
      expect(env.ASR_UPSTREAM_URL).toBe('http://parakeet-asr.asr:8000/v1');
      expect(env.ASR_UPSTREAM_KEY).toBe('k');
      expect(env.ASR_MODEL).toBe('parakeet');
    });

    it('accepts an upstream without a key or model', () => {
      const env = load({ LLM_UPSTREAM_URL: 'https://llm.example.com/v1' });
      expect(env.LLM_UPSTREAM_KEY).toBeUndefined();
      expect(env.LLM_MODEL).toBeUndefined();
    });

    it('rejects a key or model without its URL', () => {
      expect(() => load({ LLM_UPSTREAM_KEY: 'k' })).toThrow(/LLM_UPSTREAM_KEY/);
      expect(() => load({ ASR_MODEL: 'm' })).toThrow(/ASR_MODEL/);
    });

    it('rejects a URL that is not http(s)', () => {
      expect(() => load({ LLM_UPSTREAM_URL: 'ftp://llm' })).toThrow(/LLM_UPSTREAM_URL/);
      expect(() => load({ ASR_UPSTREAM_URL: 'not a url' })).toThrow(/ASR_UPSTREAM_URL/);
    });
  });
});
