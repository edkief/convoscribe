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

  it('gives users every service unless DEFAULT_USER_SERVICES says otherwise (#145)', () => {
    const services = (value: string) =>
      loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey, DEFAULT_USER_SERVICES: value }).DEFAULT_USER_SERVICES;
    expect(services('')).toEqual(['sync', 'llm', 'asr', 'decision']);
    expect(loadEnv({ ...base, AUTH_MODE: 'api-key', SYNC_API_KEY: apiKey }).DEFAULT_USER_SERVICES).toHaveLength(4);
    expect(services(' LLM, sync ,')).toEqual(['sync', 'llm']);
    expect(services('none')).toEqual([]);
    expect(() => services('sync,web')).toThrow(/DEFAULT_USER_SERVICES/);
    expect(() => services(',')).toThrow(/DEFAULT_USER_SERVICES/);
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
      expect(() => load({ DECISION_UPSTREAM_KEY: 'k' })).toThrow(/DECISION_UPSTREAM_KEY/);
    });

    it('accepts a decision service with a key and model (#101)', () => {
      const env = load({ DECISION_UPSTREAM_URL: 'http://laya.llama:8080/', DECISION_UPSTREAM_KEY: 'k', DECISION_MODEL: 'english' });
      expect(env.DECISION_UPSTREAM_URL).toBe('http://laya.llama:8080');
      expect(env.DECISION_UPSTREAM_KEY).toBe('k');
      expect(env.DECISION_MODEL).toBe('english');
      expect(load({}).DECISION_UPSTREAM_URL).toBeUndefined();
    });

    it('rejects a URL that is not http(s)', () => {
      expect(() => load({ LLM_UPSTREAM_URL: 'ftp://llm' })).toThrow(/LLM_UPSTREAM_URL/);
      expect(() => load({ ASR_UPSTREAM_URL: 'not a url' })).toThrow(/ASR_UPSTREAM_URL/);
    });

    it('sends 24 000 transcript characters per summary call unless told otherwise (#117)', () => {
      expect(load({}).LLM_MAX_TRANSCRIPT_CHARS).toBe(24_000);
      expect(load({ LLM_MAX_TRANSCRIPT_CHARS: '' }).LLM_MAX_TRANSCRIPT_CHARS).toBe(24_000);
      expect(load({ LLM_MAX_TRANSCRIPT_CHARS: '60000' }).LLM_MAX_TRANSCRIPT_CHARS).toBe(60_000);
      expect(() => load({ LLM_MAX_TRANSCRIPT_CHARS: '500' })).toThrow(/LLM_MAX_TRANSCRIPT_CHARS/);
      expect(() => load({ LLM_MAX_TRANSCRIPT_CHARS: 'lots' })).toThrow(/LLM_MAX_TRANSCRIPT_CHARS/);
    });

    it('reads how to ask the language model for low reasoning as a JSON object (#136)', () => {
      const url = { LLM_UPSTREAM_URL: 'https://llm.example.com/v1' };
      expect(load(url).LLM_LOW_REASONING).toBeUndefined();
      expect(load({ ...url, LLM_LOW_REASONING: '' }).LLM_LOW_REASONING).toBeUndefined();
      expect(load({ ...url, LLM_LOW_REASONING: '{"chat_template_kwargs":{"reasoning_strength":"none"}}' }).LLM_LOW_REASONING).toEqual({
        chat_template_kwargs: { reasoning_strength: 'none' },
      });
      expect(() => load({ ...url, LLM_LOW_REASONING: 'low' })).toThrow(/LLM_LOW_REASONING/);
      expect(() => load({ ...url, LLM_LOW_REASONING: '["low"]' })).toThrow(/LLM_LOW_REASONING/);
      expect(() => load({ ...url, LLM_LOW_REASONING: 'null' })).toThrow(/LLM_LOW_REASONING/);
      expect(() => load({ LLM_LOW_REASONING: '{"reasoning_effort":"low"}' })).toThrow(/LLM_LOW_REASONING/);
    });

    it('checks talking points with the decision service only when switched on (#136)', () => {
      expect(load({}).DECISION_FOR_TALKING_POINTS).toBe('off');
      expect(load({ DECISION_FOR_TALKING_POINTS: '' }).DECISION_FOR_TALKING_POINTS).toBe('off');
      expect(load({ DECISION_FOR_TALKING_POINTS: 'on' }).DECISION_FOR_TALKING_POINTS).toBe('on');
      expect(() => load({ DECISION_FOR_TALKING_POINTS: 'yes' })).toThrow(/DECISION_FOR_TALKING_POINTS/);
    });
  });
});
