import { beforeEach, describe, expect, it } from 'vitest';
import { backoffMs, PART_BYTES, SignedOut, UploadRunner, type QueueStatus, type QueuedRecording, type QueueStore } from './upload-queue';

const SHA = 'a'.repeat(64);

/** The #163 routes, in memory, with failures to inject. */
class FakeServer {
  blobs = new Set<string>();
  uploads = new Map<string, Map<number, number>>();
  sessions = new Map<string, string>();
  calls: string[] = [];
  /** Throws like a dropped connection for the next request matching it (before or after the server acts). */
  drop: { match: RegExp; after: boolean } | null = null;
  /** Answers the next request matching it with this instead. */
  answer: { match: RegExp; res: () => Response } | null = null;
  private nextId = 1;

  fetch = (async (input: RequestInfo | URL, init?: RequestInit) => {
    const method = init?.method ?? 'GET';
    const url = String(input);
    const call = `${method} ${url.replace(/^\/api\/web\/v1/, '')}`;
    this.calls.push(call);
    if (this.answer?.match.test(call)) {
      const { res } = this.answer;
      this.answer = null;
      return res();
    }
    const drop = this.drop?.match.test(call) ? this.drop : null;
    if (drop && !drop.after) {
      this.drop = null;
      throw new TypeError('Failed to fetch');
    }
    const res = await this.handle(method, url, init);
    if (drop) {
      this.drop = null;
      throw new TypeError('Failed to fetch');
    }
    return res;
  }) as typeof fetch;

  private async handle(method: string, url: string, init?: RequestInit): Promise<Response> {
    const json = (body: unknown, status = 200) => Response.json(body, { status });
    let m: RegExpExecArray | null;
    if (method === 'POST' && (m = /blobs\/(\w+)\/uploads$/.exec(url))) {
      if (this.blobs.has(m[1])) return json({ exists: true });
      const id = `u${this.nextId++}`;
      this.uploads.set(id, new Map());
      return json({ id }, 201);
    }
    if (method === 'GET' && (m = /uploads\/(\w+)$/.exec(url))) {
      const parts = this.uploads.get(m[1]);
      if (!parts) return json({ error: 'unknown_upload' }, 404);
      return json({ parts: [...parts].map(([n, size]) => ({ n, size })) });
    }
    if (method === 'PUT' && (m = /uploads\/(\w+)\/parts\/(\d+)$/.exec(url))) {
      const parts = this.uploads.get(m[1]);
      if (!parts) return json({ error: 'unknown_upload' }, 404);
      parts.set(Number(m[2]), (init!.body as Blob).size);
      return new Response(null, { status: 204 });
    }
    if (method === 'POST' && (m = /blobs\/(\w+)\/uploads\/(\w+)\/complete$/.exec(url))) {
      const parts = this.uploads.get(m[2]);
      if (!parts) return json({ error: 'unknown_upload' }, 404);
      const size = [...parts.values()].reduce((a, b) => a + b, 0);
      this.uploads.delete(m[2]);
      this.blobs.add(m[1]);
      return json({ size });
    }
    if (method === 'POST' && url.endsWith('/sessions')) {
      const body = init!.body as string;
      const { syncId, audioSha } = JSON.parse(body);
      if (!this.blobs.has(audioSha)) return json({ error: 'missing_blobs' }, 422);
      const existing = this.sessions.get(syncId);
      if (existing !== undefined && existing !== body) return json({ error: 'conflict' }, 409);
      this.sessions.set(syncId, body);
      return json({ syncId, revision: 1 }, existing === undefined ? 201 : 200);
    }
    return json({ error: 'not_found' }, 404);
  }
}

class MemoryStore implements QueueStore {
  items = new Map<string, QueuedRecording>();
  async all() {
    return [...this.items.values()];
  }
  async put(item: QueuedRecording) {
    this.items.set(item.syncId, item);
  }
  async remove(syncId: string) {
    this.items.delete(syncId);
  }
}

const recording = (size: number, patch: Partial<QueuedRecording> = {}): QueuedRecording => ({
  syncId: '00000000-0000-4000-8000-000000000001',
  audioSha: SHA,
  size,
  durationMs: 1000,
  createdAt: 1_700_000_000_000,
  language: 'en',
  zone: 'Europe/Paris',
  asrModel: 'asr-en-kroko-2025-08-06',
  lines: [{ text: 'Hello', startMs: 0, endMs: 900, tokens: [{ text: ' Hello', ms: 100 }] }],
  uploadId: null,
  stored: false,
  failed: null,
  queuedAt: 1,
  ...patch,
});

let server: FakeServer;
let store: MemoryStore;
let removed: string[];
let runner: UploadRunner;

beforeEach(() => {
  server = new FakeServer();
  store = new MemoryStore();
  removed = [];
  runner = new UploadRunner({
    fetch: server.fetch,
    store,
    file: async (item) => new Blob([new Uint8Array(item.size)]),
    removeFile: async (item) => {
      removed.push(item.syncId);
    },
  });
});

const puts = () => server.calls.filter((c) => c.startsWith('PUT')).map((c) => Number(c.split('/').at(-1)));

describe('UploadRunner (#164)', () => {
  it('sends the recording in 8 MB parts, then creates the session and forgets it', async () => {
    const item = recording(2 * PART_BYTES + 1000);
    await store.put(item);
    await runner.send(item);
    expect(puts()).toEqual([1, 2, 3]);
    expect(server.blobs.has(SHA)).toBe(true);
    expect(server.sessions.size).toBe(1);
    expect(JSON.parse(server.sessions.get(item.syncId)!)).toMatchObject({ asrModel: item.asrModel, lines: item.lines });
    expect(store.items.size).toBe(0);
    expect(removed).toEqual([item.syncId]);
  });

  it('resumes from the parts the server has after the connection drops', async () => {
    const item = recording(2 * PART_BYTES + 1000);
    await store.put(item);
    server.drop = { match: /parts\/2$/, after: false };
    await expect(runner.send(item)).rejects.toThrow('offline');
    const kept = store.items.get(item.syncId)!;
    expect(kept.uploadId).toBe('u1');
    server.calls = [];
    await runner.send(kept);
    expect(puts()).toEqual([2, 3]);
    expect(server.sessions.size).toBe(1);
  });

  it('sends no part for a recording the server already has', async () => {
    server.blobs.add(SHA);
    const item = recording(1000);
    await store.put(item);
    await runner.send(item);
    expect(puts()).toEqual([]);
    expect(server.sessions.size).toBe(1);
  });

  it('creates one session when the answer to the create is lost and it is sent again', async () => {
    const item = recording(1000);
    await store.put(item);
    server.drop = { match: /POST \/sessions/, after: true };
    await expect(runner.send(item)).rejects.toThrow('offline');
    await runner.send(store.items.get(item.syncId)!);
    expect(server.sessions.size).toBe(1);
    expect(store.items.size).toBe(0);
  });

  it('starts over when the server no longer knows the upload, or completed it before the answer was lost', async () => {
    const item = recording(1000);
    await store.put(item);
    server.drop = { match: /complete$/, after: true };
    await expect(runner.send(item)).rejects.toThrow('offline');
    server.calls = [];
    await runner.send(store.items.get(item.syncId)!);
    // The upload is gone (404); starting again finds the recording stored.
    expect(server.calls.slice(0, 2)).toEqual([`GET /blobs/${SHA}/uploads/u1`, `POST /blobs/${SHA}/uploads`]);
    expect(server.sessions.size).toBe(1);
  });

  it('retries on a sign-in page or a server error, and gives up on a refusal', async () => {
    const item = recording(1000);
    await store.put(item);
    server.answer = { match: /POST \/blobs/, res: () => new Response('<html>Sign in</html>', { status: 200 }) };
    await expect(runner.send(item)).rejects.toThrow('unexpected_answer_200');
    server.answer = { match: /POST \/blobs/, res: () => Response.json({ error: 'boom' }, { status: 502 }) };
    await expect(runner.send(item)).rejects.toThrow('boom');
    server.answer = { match: /POST \/blobs/, res: () => Response.json({ error: 'quota_exceeded' }, { status: 507 }) };
    await expect(runner.send(item)).rejects.toThrow('No room left');
  });

  it('marks a recording another session already uses as failed and goes on with the next', async () => {
    const taken = recording(1000);
    server.blobs.add(SHA);
    server.sessions.set(taken.syncId, 'something else');
    const next = recording(1000, { syncId: '00000000-0000-4000-8000-000000000002', queuedAt: 2 });
    await store.put(taken);
    await store.put(next);
    const created: string[] = [];
    const run = runner.run((item) => {
      created.push(item.syncId);
      runner.stop();
    });
    await run;
    expect(created).toEqual([next.syncId]);
    expect(store.items.get(taken.syncId)?.failed).toMatch(/Another session/);
  });

  it('waits with backoff after a failure, and a kick tries again at once', async () => {
    const waits: number[] = [];
    let failures = 2;
    const flaky = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (failures-- > 0) throw new TypeError('Failed to fetch');
      return server.fetch(input, init);
    }) as typeof fetch;
    runner = new UploadRunner({
      fetch: flaky,
      store,
      file: async (item) => new Blob([new Uint8Array(item.size)]),
      removeFile: async () => {},
      sleep: async (ms) => {
        waits.push(ms);
      },
    });
    await store.put(recording(1000));
    await runner.run(() => runner.stop());
    expect(waits).toEqual([2000, 4000]);
    expect(server.sessions.size).toBe(1);
  });

  it('takes a 401 or a redirect to sign in as a signed-out browser (#165)', async () => {
    const item = recording(1000);
    await store.put(item);
    server.answer = { match: /POST \/blobs/, res: () => Response.json({ error: 'unauthorized' }, { status: 401 }) };
    await expect(runner.send(item)).rejects.toBeInstanceOf(SignedOut);
    // What fetch returns for a redirect with `redirect: 'manual'`.
    const redirect = { type: 'opaqueredirect', status: 0, text: async () => '' } as unknown as Response;
    server.answer = { match: /POST \/blobs/, res: () => redirect };
    await expect(runner.send(item)).rejects.toBeInstanceOf(SignedOut);
    expect(store.items.get(item.syncId)?.failed).toBeNull();
  });

  it('waits, without backing off or failing, while signed out, and goes on after signing in (#165)', async () => {
    const waits: number[] = [];
    const seen: QueueStatus[] = [];
    let signedOut = 2;
    const expiring = (async (input: RequestInfo | URL, init?: RequestInit) => {
      expect(init?.redirect).toBe('manual');
      if (signedOut-- > 0) return Response.json({ error: 'unauthorized' }, { status: 401 });
      return server.fetch(input, init);
    }) as typeof fetch;
    runner = new UploadRunner({
      fetch: expiring,
      store,
      file: async (item) => new Blob([new Uint8Array(item.size)]),
      removeFile: async () => {},
      sleep: async (ms) => {
        waits.push(ms);
      },
    });
    runner.subscribe((status) => seen.push(status));
    await store.put(recording(1000));
    await runner.run(() => runner.stop());
    expect(waits).toEqual([60_000, 60_000]);
    expect(seen.some((s) => s.signedOut)).toBe(true);
    expect(seen.at(-1)?.signedOut).toBe(false);
    expect(server.sessions.size).toBe(1);
  });

  it('backs off from 2 s to at most a minute', () => {
    expect([1, 2, 3, 4, 5, 6, 7].map(backoffMs)).toEqual([2000, 4000, 8000, 16000, 32000, 60000, 60000]);
  });
});
