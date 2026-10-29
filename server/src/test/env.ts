import { vi } from 'vitest';
import { resetAuth } from '@/lib/auth';
import { resetEnv } from '@/lib/env';

export const TEST_API_KEY = 'test-key-0123456789abcdef0123456789';

/** Stub a complete server environment, with `overrides` on top. */
export function stubServerEnv(overrides: Record<string, string> = {}) {
  const values: Record<string, string> = {
    DATABASE_URL: 'postgres://u:p@localhost:5432/db',
    S3_BUCKET: 'bucket',
    S3_ACCESS_KEY_ID: 'id',
    S3_SECRET_ACCESS_KEY: 'secret',
    AUTH_MODE: 'api-key',
    SYNC_API_KEY: TEST_API_KEY,
    AUTHZ_PROXY_URL: '',
    ...overrides,
  };
  for (const [name, value] of Object.entries(values)) vi.stubEnv(name, value);
  resetEnv();
  resetAuth();
}
