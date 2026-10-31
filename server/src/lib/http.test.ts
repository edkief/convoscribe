import { afterEach, describe, expect, it, vi } from 'vitest';
import { stubServerEnv } from '@/test/env';
import { SESSION_COOKIE, newSessionValue } from './auth';
import { sameOrigin, webAuthed } from './http';

const post = (headers: Record<string, string>, url = 'http://server/api/web/v1/x') =>
  new Request(url, { method: 'POST', headers });

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('sameOrigin', () => {
  it('accepts an Origin naming the host the request was sent to', () => {
    expect(sameOrigin(post({ origin: 'http://server' }))).toBe(true);
    expect(sameOrigin(post({ origin: 'https://sync.example.com', host: 'sync.example.com' }))).toBe(true);
    expect(
      sameOrigin(post({ origin: 'https://sync.example.com', host: 'pod:3000', 'x-forwarded-host': 'sync.example.com' })),
    ).toBe(true);
  });

  it('refuses another site, a missing Origin and a malformed one', () => {
    expect(sameOrigin(post({ origin: 'https://evil.example', host: 'sync.example.com' }))).toBe(false);
    expect(sameOrigin(post({ origin: 'https://sync.example.com.evil.example', host: 'sync.example.com' }))).toBe(false);
    expect(sameOrigin(post({}))).toBe(false);
    expect(sameOrigin(post({ origin: 'null' }))).toBe(false);
  });
});

describe('webAuthed', () => {
  const ok = () => Promise.resolve(new Response('ok'));
  const cookie = () => `${SESSION_COOKIE}=${newSessionValue()}`;

  it('refuses a state-changing request from another site before anything else', async () => {
    stubServerEnv();
    const res = await webAuthed(post({ origin: 'https://evil.example', cookie: cookie() }), ok);
    expect(res.status).toBe(403);
    expect(await res.json()).toEqual({ error: 'cross_site' });
  });

  it('lets a same-site request or a read through once signed in, and answers 401 otherwise', async () => {
    stubServerEnv();
    expect((await webAuthed(post({ origin: 'http://server', cookie: cookie() }), ok)).status).toBe(200);
    expect((await webAuthed(new Request('http://server/x', { headers: { cookie: cookie() } }), ok)).status).toBe(200);
    expect((await webAuthed(new Request('http://server/x'), ok)).status).toBe(401);
  });
});
