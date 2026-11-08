import { describe, expect, it } from 'vitest';
import { setServices } from '@/lib/access';
import { noteActivity } from '@/lib/owners';
import { stubServerEnv } from '@/test/env';
import { request, setupSyncServer } from '@/test/http';
import { GET } from './route';

setupSyncServer();

const config = (headers: Record<string, string> = {}) => GET(request('/api/sync/v1/config', { headers }));

describe('GET /api/sync/v1/config (#93)', () => {
  it('offers nothing when no upstream is configured', async () => {
    const res = await config();
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ llm: null, transcription: null, decision: null });
  });

  it('names each offered service’s model, and never its address or key', async () => {
    stubServerEnv({
      LLM_UPSTREAM_URL: 'http://llm.internal:8080/v1',
      LLM_UPSTREAM_KEY: 'llm-secret',
      LLM_MODEL: 'qwen3-8b',
      ASR_UPSTREAM_URL: 'http://parakeet-asr.asr:8000/v1',
      DECISION_UPSTREAM_URL: 'http://laya.llama:8080',
      DECISION_UPSTREAM_KEY: 'laya-secret',
    });
    const res = await config();
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ llm: { model: 'qwen3-8b' }, transcription: { model: null }, decision: { model: null } });
    for (const secret of ['llm.internal', 'llm-secret', 'parakeet-asr', 'laya.llama', 'laya-secret']) expect(text).not.toContain(secret);
  });

  it('is 401 without a valid key', async () => {
    for (const authorization of ['', 'Bearer wrong']) {
      const res = await config({ authorization });
      expect(res.status).toBe(401);
    }
  });

  describe('offers only what the user was given (#145)', () => {
    const everything = {
      LLM_UPSTREAM_URL: 'http://llm.internal:8080/v1',
      LLM_MODEL: 'qwen3-8b',
      ASR_UPSTREAM_URL: 'http://parakeet-asr.asr:8000/v1',
      DECISION_UPSTREAM_URL: 'http://laya.llama:8080',
    };

    it('leaves out a service an admin did not give the user', async () => {
      stubServerEnv(everything);
      await noteActivity('default');
      await setServices('default', ['sync', 'asr']);
      expect(await (await config()).json()).toEqual({ llm: null, transcription: { model: null }, decision: null });
    });

    it('answers a user without sync too, from the default', async () => {
      stubServerEnv({ ...everything, DEFAULT_USER_SERVICES: 'llm' });
      const res = await config();
      expect(res.status).toBe(200);
      expect(await res.json()).toEqual({ llm: { model: 'qwen3-8b' }, transcription: null, decision: null });
    });
  });
});
