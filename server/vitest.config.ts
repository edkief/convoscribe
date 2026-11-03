import path from 'node:path';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/*.test.{ts,tsx}'],
    // Each worker holds in-memory Postgres (PGlite) at ~1.3 GB peak, and the
    // default (one per core) counts the build node's cores, not the pod's
    // memory limit: the image build (`pnpm check` in the Dockerfile) was
    // OOM-killed at 15 GiB. Override with VITEST_MAX_WORKERS.
    maxWorkers: Number(process.env.VITEST_MAX_WORKERS) || 4,
  },
  resolve: {
    alias: { '@': path.resolve(import.meta.dirname, './src') },
  },
});
