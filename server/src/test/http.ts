import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { afterEach, beforeEach, vi } from 'vitest';
import { FsBlobStore, setBlobStore } from '@/lib/blob-store';
import { resetActivity } from '@/lib/owners';
import { closeTestDb, createTestDb } from './db';
import { stubServerEnv, TEST_API_KEY } from './env';

/** Request headers for an authenticated device. */
export const AUTH = { authorization: `Bearer ${TEST_API_KEY}` };

/** The context a dynamic route handler receives. */
export const ctx = <P extends Record<string, string>>(params: P) => ({ params: Promise.resolve(params) });

export const sha256 = (bytes: Uint8Array | string) => createHash('sha256').update(bytes).digest('hex');

/** A request to `path` on the test server, authenticated unless `headers` says otherwise. */
export function request(path: string, init: RequestInit & { headers?: Record<string, string> } = {}) {
  return new Request(`http://server${path}`, { ...init, headers: { ...AUTH, ...init.headers } });
}

/** A fresh database and filesystem blob store around every test of the file. */
export function setupSyncServer() {
  let root = '';
  beforeEach(async () => {
    stubServerEnv();
    await createTestDb();
    resetActivity();
    root = await mkdtemp(path.join(tmpdir(), 'blobs-'));
    setBlobStore(new FsBlobStore(root));
  });
  afterEach(async () => {
    await closeTestDb();
    setBlobStore(undefined);
    vi.unstubAllEnvs();
    await rm(root, { recursive: true, force: true });
  });
}
