import { and, eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { blobs, cleanupRuns, uploadParts, uploads } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { blobKeys, blobStore } from './blob-store';
import { claimBlob, putPart, startUpload } from './blobs';
import { CLEANUP_GRACE_MS, cleanupStatus, runCleanup, scheduledCleanup, startManualCleanup, type CleanupReport } from './cleanup';
import { db } from './db';
import { deleteDocument, getDocumentMeta, restoreSession, TRASH_MS } from './documents';
import { HttpError } from './http';

setupSyncServer();

const DAY = 24 * 60 * 60 * 1000;
const at = (ms: number) => new Date(Date.now() + ms);
const pastGrace = () => at(CLEANUP_GRACE_MS + DAY);
const quiet = () => {};
const exists = async (key: string) => (await blobStore().head(key)) !== null;

async function run(dryRun: boolean, now = new Date()): Promise<CleanupReport> {
  const result = await runCleanup({ dryRun, trigger: 'manual', now, log: quiet });
  if (!('report' in result)) throw new Error('skipped');
  return result.report;
}

const nothing: CleanupReport = {
  trash: { sessions: 0 },
  recordings: { count: 0, bytes: 0 },
  manifests: { count: 0, bytes: 0 },
  uploads: { count: 0, bytes: 0 },
  failures: 0,
};

/** A session edited once (its first manifest now unused) and a recording nothing refers to. */
async function library() {
  const audio = await storeBlob('alice', 'the recording');
  const first = await pushManifest('alice', sessionManifest('s1', { audio }));
  const second = await pushManifest('alice', sessionManifest('s1', { audio, title: 'Renamed' }), first.revision);
  const orphan = await storeBlob('alice', 'an abandoned retake');
  return {
    audio,
    orphan,
    oldManifest: blobKeys.manifest('alice', 'session', 's1', first.manifestHash!),
    manifest: blobKeys.manifest('alice', 'session', 's1', second.manifestHash!),
  };
}

describe('runCleanup (#81)', () => {
  it('leaves everything newer than the grace period', async () => {
    const lib = await library();
    expect(await run(false)).toEqual(nothing);
    expect(await exists(lib.oldManifest)).toBe(true);
    expect(await exists(blobKeys.blob('alice', lib.orphan.sha256))).toBe(true);
  });

  it('removes old manifests and unused recordings, keeping what sessions use', async () => {
    const lib = await library();
    const report = await run(false, pastGrace());
    expect(report.recordings).toEqual({ count: 1, bytes: lib.orphan.size });
    expect(report.manifests.count).toBe(1);
    expect(await exists(lib.oldManifest)).toBe(false);
    expect(await exists(blobKeys.blob('alice', lib.orphan.sha256))).toBe(false);
    expect(await db().select().from(blobs).where(eq(blobs.sha256, lib.orphan.sha256))).toEqual([]);
    expect(await exists(lib.manifest)).toBe(true);
    expect(await exists(blobKeys.blob('alice', lib.audio.sha256))).toBe(true);
    expect(await run(false, pastGrace())).toEqual(nothing);
  });

  it('previews a run without removing anything', async () => {
    const lib = await library();
    const preview = await run(true, pastGrace());
    expect(preview.recordings.count).toBe(1);
    expect(preview.manifests.count).toBe(1);
    expect(await exists(lib.oldManifest)).toBe(true);
    expect(await exists(blobKeys.blob('alice', lib.orphan.sha256))).toBe(true);
    expect(await run(false, pastGrace())).toEqual(preview);
  });

  it('keeps a trashed session for 30 days, then purges it and its data', async () => {
    const audio = await storeBlob('alice', 'deleted recording');
    const pushed = await pushManifest('alice', sessionManifest('s1', { audio }));
    await deleteDocument('alice', 'session', 's1', pushed.revision, 65_000);
    const manifest = blobKeys.manifest('alice', 'session', 's1', pushed.manifestHash!);

    expect(await run(false, pastGrace())).toEqual(nothing);
    expect(await exists(manifest)).toBe(true);

    const expired = at(TRASH_MS + DAY);
    const preview = await run(true, expired);
    expect(preview).toMatchObject({ trash: { sessions: 1 }, recordings: { count: 1 }, manifests: { count: 1 } });
    expect(await exists(manifest)).toBe(true);
    expect(await run(false, expired)).toEqual(preview);
    expect(await exists(manifest)).toBe(false);
    expect(await exists(blobKeys.blob('alice', audio.sha256))).toBe(false);
    expect(await getDocumentMeta('alice', 'session', 's1')).toMatchObject({ deleted: true });
    await expect(restoreSession('alice', 's1')).rejects.toMatchObject({ status: 404 });
  });

  it('keeps a recording a live session shares with a purged one', async () => {
    const audio = await storeBlob('alice', 'shared');
    const gone = await pushManifest('alice', sessionManifest('s1', { audio }));
    await pushManifest('alice', sessionManifest('s2', { audio }));
    await deleteDocument('alice', 'session', 's1', gone.revision, 65_000);
    const report = await run(false, at(TRASH_MS + DAY));
    expect(report.trash.sessions).toBe(1);
    expect(report.recordings.count).toBe(0);
    expect(await exists(blobKeys.blob('alice', audio.sha256))).toBe(true);
  });

  it('aborts uploads abandoned for longer than the grace period', async () => {
    const sha = 'a'.repeat(64);
    const started = await startUpload('alice', sha);
    if (!('id' in started)) throw new Error('expected an upload');
    await putPart('alice', sha, started.id, 1, new Uint8Array(100));
    expect((await run(false)).uploads.count).toBe(0);
    expect((await run(true, pastGrace())).uploads).toEqual({ count: 1, bytes: 100 });
    expect(await db().select().from(uploads)).toHaveLength(1);
    expect((await run(false, pastGrace())).uploads).toEqual({ count: 1, bytes: 100 });
    expect(await db().select().from(uploads)).toEqual([]);
    expect(await db().select().from(uploadParts)).toEqual([]);
  });

  it('removes objects an interrupted upload left without a row, and nothing it did not write', async () => {
    const owner = 'Ann Smith@example.com';
    const sha = 'b'.repeat(64);
    const store = blobStore();
    await store.put(blobKeys.blob(owner, sha), new Uint8Array(10));
    await store.put(blobKeys.staging(owner, 'dead-upload'), new Uint8Array(20));
    await store.put('users/alice/notes.txt', new Uint8Array(1));
    await store.put('elsewhere/thing', new Uint8Array(1));
    const report = await run(false, pastGrace());
    expect(report.recordings).toEqual({ count: 1, bytes: 10 });
    expect(report.uploads).toEqual({ count: 1, bytes: 20 });
    expect(await exists(blobKeys.blob(owner, sha))).toBe(false);
    expect(await exists(blobKeys.staging(owner, 'dead-upload'))).toBe(false);
    expect(await exists('users/alice/notes.txt')).toBe(true);
    expect(await exists('elsewhere/thing')).toBe(true);
  });

  it('keeps a recording a device was just told is stored', async () => {
    const claimed = await storeBlob('alice', 'about to be pushed');
    const unclaimed = await storeBlob('alice', 'nobody wants this');
    await db().update(blobs).set({ createdAt: new Date(Date.now() - CLEANUP_GRACE_MS - DAY) });
    // The device asks whether the server has it (HEAD or a new upload) before pushing.
    expect(await claimBlob('alice', claimed.sha256)).toBe(claimed.size);
    expect((await run(false)).recordings.count).toBe(1);
    expect(await exists(blobKeys.blob('alice', claimed.sha256))).toBe(true);
    await pushManifest('alice', sessionManifest('s1', { audio: claimed }));
    // A push referring to a recording removed meanwhile is refused, never stored broken.
    await expect(pushManifest('alice', sessionManifest('s2', { audio: unclaimed }))).rejects.toMatchObject({
      status: 422,
      code: 'missing_blobs',
    } satisfies Partial<HttpError>);
  });

  it('records each run, and skips one while another of its kind is going', async () => {
    const result = await runCleanup({ dryRun: true, trigger: 'manual', log: quiet });
    const [row] = await db().select().from(cleanupRuns);
    expect(row).toMatchObject({ dryRun: true, trigger: 'manual', report: nothing, error: null });
    expect(row.finishedAt).not.toBeNull();
    expect(result).toEqual({ id: row.id, report: nothing });

    await db().insert(cleanupRuns).values({ dryRun: false, trigger: 'manual' });
    expect(await runCleanup({ dryRun: false, trigger: 'manual', log: quiet })).toEqual({ skipped: 'running' });
    expect('report' in (await runCleanup({ dryRun: true, trigger: 'manual', log: quiet }))).toBe(true);
    // One that never finished long ago died with its server.
    expect('report' in (await runCleanup({ dryRun: false, trigger: 'manual', now: at(7 * 60 * 60 * 1000), log: quiet }))).toBe(
      true,
    );
  });

  it('runs on schedule once a day', async () => {
    const log = console.log;
    console.log = quiet;
    try {
      expect(await scheduledCleanup('dry-run')).toBe(true);
      expect(await scheduledCleanup('dry-run', at(DAY - 60_000))).toBe(false);
      expect(await scheduledCleanup('on', at(DAY + 60_000))).toBe(true);
    } finally {
      console.log = log;
    }
    const runs = await db().select().from(cleanupRuns).where(and(eq(cleanupRuns.trigger, 'scheduled')));
    expect(runs.map((r) => r.dryRun)).toEqual([true, false]);
  });
});

describe('clean-up from the admin pages (#82)', () => {
  const finished = async (dryRun: boolean) => {
    for (let i = 0; i < 200; i++) {
      const status = await cleanupStatus();
      if (!(dryRun ? status.preview : status.run).running) return status;
      await new Promise((resolve) => setTimeout(resolve, 10));
    }
    throw new Error('still running');
  };

  it('starts in the background and shows the result', async () => {
    const lib = await library();
    await db().update(blobs).set({ createdAt: at(-CLEANUP_GRACE_MS - DAY) }).where(eq(blobs.sha256, lib.orphan.sha256));
    const { id } = await startManualCleanup(true);
    expect(id).toBeGreaterThan(0);
    const status = await finished(true);
    expect(status.preview.last).toMatchObject({ trigger: 'manual', failed: false, report: { recordings: { count: 1, bytes: lib.orphan.size } } });
    expect(status.preview.last!.finishedAt).toBeGreaterThan(0);
    expect(status.run.last).toBeNull();
    expect(await exists(blobKeys.blob('alice', lib.orphan.sha256))).toBe(true);

    await startManualCleanup(false);
    const after = await finished(false);
    expect(after.run.last).toMatchObject({ report: { recordings: { count: 1, bytes: lib.orphan.size } } });
    expect(await exists(blobKeys.blob('alice', lib.orphan.sha256))).toBe(false);
  });

  it('refuses a run while one of the same kind is going, and reports it going', async () => {
    await db().insert(cleanupRuns).values({ dryRun: false, trigger: 'scheduled' });
    await expect(startManualCleanup(false)).rejects.toMatchObject({ status: 409, code: 'cleanup_running' });
    const status = await cleanupStatus();
    expect(status.run).toEqual({ running: true, last: null });
    expect(status.preview.running).toBe(false);
  });

  it('shows a failed run without its error text, and a dead one as failed', async () => {
    await db()
      .insert(cleanupRuns)
      .values({ dryRun: false, trigger: 'manual', startedAt: at(-DAY), finishedAt: at(-DAY), report: nothing, error: 'S3 said secret-endpoint' });
    await db().insert(cleanupRuns).values({ dryRun: true, trigger: 'scheduled', startedAt: at(-DAY) });
    const status = await cleanupStatus();
    expect(status.run).toMatchObject({ running: false, last: { failed: true, report: nothing } });
    expect(status.preview).toMatchObject({ running: false, last: { failed: true, finishedAt: null } });
    expect(JSON.stringify(status)).not.toContain('secret-endpoint');
  });

});
