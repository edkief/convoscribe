import { and, desc, eq, gte, isNotNull, isNull, lt, or, sql } from 'drizzle-orm';
import { blobs, cleanupRuns, documents, uploadParts, uploads } from '@/db/schema';
import { blobKeys, blobStore } from './blob-store';
import { db, type Db } from './db';
import { lockOwner, purgeTrash, TRASH_MS, trashedWhere } from './documents';
import { env } from './env';
import { HttpError } from './http';

/**
 * How old anything must be before clean-up removes it: a recording not yet
 * referred to, a manifest no longer current, an upload not finished. Pushes
 * and uploads take seconds to hours, so a week leaves every one in progress
 * alone.
 */
export const CLEANUP_GRACE_MS = 7 * 24 * 60 * 60 * 1000;

/** How often the scheduled clean-up runs. */
export const CLEANUP_EVERY_MS = 24 * 60 * 60 * 1000;

/** A run that has not finished after this long is taken to have died with its server. */
const STALE_RUN_MS = 6 * 60 * 60 * 1000;

type Tally = { count: number; bytes: number };

/** What a run removed, or for a dry run would remove. */
export type CleanupReport = {
  /** Sessions purged from the trash after TRASH_DAYS. */
  trash: { sessions: number };
  /** Recordings no live or trashed session refers to. */
  recordings: Tally;
  /** Manifests that are neither a document's current one nor a trashed session's. */
  manifests: Tally;
  /** Uploads abandoned by a device, and assembled uploads left behind. */
  uploads: Tally;
  /** Items that could not be removed; the next run tries again. */
  failures: number;
};

export type CleanupResult = { id: number; report: CleanupReport } | { skipped: 'running' };

type Options = {
  dryRun: boolean;
  trigger: 'scheduled' | 'manual';
  /** The time the run takes as now (tests only). */
  now?: Date;
  log?: (message: string) => void;
};

const emptyReport = (): CleanupReport => ({
  trash: { sessions: 0 },
  recordings: { count: 0, bytes: 0 },
  manifests: { count: 0, bytes: 0 },
  uploads: { count: 0, bytes: 0 },
  failures: 0,
});

/**
 * Remove storage nothing uses any more (#79, decision 7; #81): sessions in
 * the trash for longer than TRASH_DAYS, then uploads abandoned for longer
 * than the grace period, then recordings and manifests no live or trashed
 * session refers to, then objects left behind by an interrupted upload.
 *
 * Every removal re-checks, under the owner's lock that pushes take too,
 * that nothing refers to the item and that it is older than the grace
 * period, so a push in progress never loses a recording or manifest it
 * depends on. A database row goes before its bytes, and the bytes go
 * before the lock is released.
 *
 * A dry run removes nothing and reports what a run would remove now,
 * counting sessions past TRASH_DAYS as already purged. Each run is recorded
 * in `cleanup_runs`. A run of the same kind still going skips this one.
 */
export async function runCleanup(options: Options): Promise<CleanupResult> {
  const started = await startCleanup(options);
  if ('skipped' in started) return started;
  return { id: started.id, report: await started.done };
}

/** Claim a run and start it without waiting for it (#82): see [runCleanup]. */
export async function startCleanup(
  options: Options,
): Promise<{ id: number; done: Promise<CleanupReport> } | { skipped: 'running' }> {
  const now = options.now ?? new Date();
  const id = await claimRun(options.dryRun, options.trigger, now);
  if (id === null) return { skipped: 'running' };
  return { id, done: execute(id, options, now) };
}

async function execute(id: number, options: Options, now: Date): Promise<CleanupReport> {
  const log = options.log ?? ((message: string) => console.warn(message));
  const report = emptyReport();
  const cutoffs = { grace: new Date(now.getTime() - CLEANUP_GRACE_MS), trash: new Date(now.getTime() - TRASH_MS) };
  const run = { ...options, log, report, ...cutoffs };
  try {
    await expiredTrash(run);
    await abandonedUploads(run);
    await unreferencedRecordings(run);
    await leftoverObjects(run);
  } catch (err) {
    await db()
      .update(cleanupRuns)
      .set({ finishedAt: new Date(), report, error: err instanceof Error ? err.message : String(err) })
      .where(eq(cleanupRuns.id, id));
    throw err;
  }
  await db().update(cleanupRuns).set({ finishedAt: new Date(), report }).where(eq(cleanupRuns.id, id));
  return report;
}

type Run = Options & { log: (message: string) => void; report: CleanupReport; grace: Date; trash: Date };

/** Record a new run, or null when one of the same kind is still going. */
async function claimRun(dryRun: boolean, trigger: string, now: Date): Promise<number | null> {
  return db().transaction(async (tx) => {
    await tx.execute(sql`select pg_advisory_xact_lock(hashtext('convoscribe:cleanup'))`);
    const [running] = await tx
      .select({ id: cleanupRuns.id })
      .from(cleanupRuns)
      .where(
        and(
          eq(cleanupRuns.dryRun, dryRun),
          isNull(cleanupRuns.finishedAt),
          gte(cleanupRuns.startedAt, new Date(now.getTime() - STALE_RUN_MS)),
        ),
      )
      .limit(1);
    if (running) return null;
    const [row] = await tx.insert(cleanupRuns).values({ dryRun, trigger, startedAt: now }).returning({ id: cleanupRuns.id });
    return row.id;
  });
}

async function attempt(run: Run, what: string, work: () => Promise<void>) {
  try {
    await work();
  } catch (err) {
    run.report.failures++;
    run.log(`clean-up: could not remove ${what}: ${err instanceof Error ? err.message : String(err)}`);
  }
}

async function expiredTrash(run: Run) {
  if (run.dryRun) {
    const [row] = await db()
      .select({ count: sql<number>`count(*)::int` })
      .from(documents)
      .where(trashedWhere({ before: run.trash }));
    run.report.trash.sessions = row?.count ?? 0;
  } else {
    run.report.trash.sessions = await purgeTrash({ before: run.trash });
  }
}

async function abandonedUploads(run: Run) {
  const rows = await db()
    .select({
      id: uploads.id,
      owner: uploads.owner,
      storeUploadId: uploads.storeUploadId,
      bytes: sql<string>`coalesce((select sum(${uploadParts.size}) from ${uploadParts} where ${uploadParts.uploadId} = ${uploads.id}), 0)`,
    })
    .from(uploads)
    .where(lt(uploads.createdAt, run.grace));
  for (const row of rows) {
    await attempt(run, `upload ${row.id} of ${row.owner}`, async () => {
      if (!run.dryRun) {
        await blobStore().abortMultipart(blobKeys.staging(row.owner, row.id), row.storeUploadId);
        await db().delete(uploads).where(eq(uploads.id, row.id));
      }
      run.report.uploads.count++;
      run.report.uploads.bytes += Number(row.bytes);
    });
  }
}

/**
 * Whether a document refers to the blob: a live one, or one in the trash
 * for less than TRASH_DAYS (a dry run has not purged the rest yet).
 */
const referenced = (trashCutoff: Date) => sql`exists (
  select 1 from document_blobs r
  join documents d on d.owner = r.owner and d.kind = r.kind and d.sync_id = r.sync_id
  where r.owner = ${blobs.owner} and r.sha256 = ${blobs.sha256}
    and (not d.deleted or d.trashed_at >= ${trashCutoff.toISOString()}::timestamptz)
)`;

async function unreferencedRecordings(run: Run) {
  const unused = and(lt(blobs.createdAt, run.grace), sql`not ${referenced(run.trash)}`);
  const rows = await db().select({ owner: blobs.owner, sha256: blobs.sha256, size: blobs.size }).from(blobs).where(unused);
  for (const row of rows) {
    await attempt(run, `recording ${row.sha256} of ${row.owner}`, async () => {
      if (run.dryRun) {
        tally(run.report.recordings, row.size);
        return;
      }
      const removed = await db().transaction(async (tx) => {
        await lockOwner(tx, row.owner);
        const [gone] = await tx
          .delete(blobs)
          .where(and(eq(blobs.owner, row.owner), eq(blobs.sha256, row.sha256), unused))
          .returning({ size: blobs.size });
        if (gone) await blobStore().delete(blobKeys.blob(row.owner, row.sha256));
        return gone;
      });
      if (removed) tally(run.report.recordings, removed.size);
    });
  }
}

const tally = (t: Tally, bytes: number) => {
  t.count++;
  t.bytes += bytes;
};

const KEY = /^users\/([^/]+)\/(?:blobs\/([0-9a-f]{64})|docs\/([^/]+)\/([^/]+)\/([0-9a-f]{64})\.json\.gz|uploads\/([^/]+))$/;

/**
 * One pass over every stored object, for what Postgres cannot list: old
 * manifests, and objects an interrupted upload or move left without a row.
 * Anything this server did not write is left alone.
 */
async function leftoverObjects(run: Run) {
  for await (const object of blobStore().list('users/')) {
    if (object.lastModified >= run.grace) continue;
    const match = KEY.exec(object.key);
    if (!match) continue;
    let owner: string;
    try {
      owner = decodeURIComponent(match[1]);
    } catch {
      continue;
    }
    const [, , sha, kind, syncId, manifestHash, uploadId] = match;
    let unused: (tx: Pick<Db, 'select'>) => Promise<boolean>;
    let t: Tally;
    if (sha) {
      t = run.report.recordings;
      unused = async (tx) =>
        !(await tx.select({ sha256: blobs.sha256 }).from(blobs).where(and(eq(blobs.owner, owner), eq(blobs.sha256, sha))))
          .length;
    } else if (manifestHash) {
      t = run.report.manifests;
      unused = async (tx) => {
        const [doc] = await tx
          .select({ current: documents.manifestHash, trashed: documents.trashedManifestHash, trashedAt: documents.trashedAt })
          .from(documents)
          .where(and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId)));
        if (!doc) return true;
        if (doc.current === manifestHash) return false;
        return !(doc.trashed === manifestHash && doc.trashedAt !== null && doc.trashedAt >= run.trash);
      };
    } else {
      t = run.report.uploads;
      unused = async (tx) => !(await tx.select({ id: uploads.id }).from(uploads).where(eq(uploads.id, uploadId))).length;
    }
    await attempt(run, `${object.key}`, async () => {
      if (run.dryRun) {
        if (await unused(db())) tally(t, object.size);
        return;
      }
      const removed = await db().transaction(async (tx) => {
        await lockOwner(tx, owner);
        if (!(await unused(tx))) return false;
        await blobStore().delete(object.key);
        return true;
      });
      if (removed) tally(t, object.size);
    });
  }
}

/**
 * An admin's *Preview* or *Run* (#82): started in the background, since a
 * pass over the whole bucket can outlast a reverse proxy's timeout. `409
 * cleanup_running` when one of the same kind is still going. The run is
 * recorded like any other, for [cleanupStatus] to show.
 */
export async function startManualCleanup(dryRun: boolean): Promise<{ id: number }> {
  const started = await startCleanup({ dryRun, trigger: 'manual' });
  if ('skipped' in started) throw new HttpError(409, 'cleanup_running');
  started.done.then(
    (report) => console.log(`manual clean-up${dryRun ? ' (dry run)' : ''}: ${summarise(report)}`),
    (err) => console.error('manual clean-up failed', err),
  );
  return { id: started.id };
}

/** A finished run as the admin pages show it: never its error text, which can hold S3's internals. */
export type CleanupRunView = {
  trigger: string;
  startedAt: number;
  finishedAt: number | null;
  report: CleanupReport | null;
  /** It stopped on an error, or died with its server. */
  failed: boolean;
};

/** One kind of run: whether one is going, and the last that is not. */
export type CleanupKindStatus = { running: boolean; last: CleanupRunView | null };

/** What the status page shows of clean-up. */
export type CleanupStatus = { mode: 'on' | 'dry-run' | 'off'; run: CleanupKindStatus; preview: CleanupKindStatus };

async function kindStatus(dryRun: boolean, now: Date): Promise<CleanupKindStatus> {
  const stale = new Date(now.getTime() - STALE_RUN_MS);
  const going = and(eq(cleanupRuns.dryRun, dryRun), isNull(cleanupRuns.finishedAt), gte(cleanupRuns.startedAt, stale));
  const [running] = await db().select({ id: cleanupRuns.id }).from(cleanupRuns).where(going).limit(1);
  const [last] = await db()
    .select({
      trigger: cleanupRuns.trigger,
      startedAt: cleanupRuns.startedAt,
      finishedAt: cleanupRuns.finishedAt,
      report: cleanupRuns.report,
      failed: sql<boolean>`${cleanupRuns.error} is not null or ${cleanupRuns.finishedAt} is null`,
    })
    .from(cleanupRuns)
    .where(and(eq(cleanupRuns.dryRun, dryRun), or(isNotNull(cleanupRuns.finishedAt), lt(cleanupRuns.startedAt, stale))))
    .orderBy(desc(cleanupRuns.startedAt), desc(cleanupRuns.id))
    .limit(1);
  return {
    running: running !== undefined,
    last: last
      ? {
          trigger: last.trigger,
          startedAt: last.startedAt.getTime(),
          finishedAt: last.finishedAt?.getTime() ?? null,
          report: last.report as CleanupReport | null,
          failed: last.failed,
        }
      : null,
  };
}

/** Clean-up's daily mode, its last run and preview, and whether either is going (#82). */
export async function cleanupStatus(now = new Date()): Promise<CleanupStatus> {
  const [run, preview] = await Promise.all([kindStatus(false, now), kindStatus(true, now)]);
  return { mode: env().CLEANUP, run, preview };
}

/** When the last scheduled run started, or null. */
export async function lastScheduledRun(): Promise<Date | null> {
  const [row] = await db()
    .select({ startedAt: cleanupRuns.startedAt })
    .from(cleanupRuns)
    .where(eq(cleanupRuns.trigger, 'scheduled'))
    .orderBy(desc(cleanupRuns.startedAt))
    .limit(1);
  return row?.startedAt ?? null;
}

/** Run the scheduled clean-up if none started in the last day; returns whether it ran. */
export async function scheduledCleanup(mode: 'on' | 'dry-run', now = new Date()): Promise<boolean> {
  const last = await lastScheduledRun();
  if (last && now.getTime() - last.getTime() < CLEANUP_EVERY_MS) return false;
  const result = await runCleanup({ dryRun: mode === 'dry-run', trigger: 'scheduled', now });
  if ('report' in result) console.log(`clean-up${mode === 'dry-run' ? ' (dry run)' : ''}: ${summarise(result.report)}`);
  return 'report' in result;
}

const summarise = (r: CleanupReport) =>
  [
    `${r.trash.sessions} session(s) purged from the trash`,
    `${r.recordings.count} recording(s), ${r.recordings.bytes} bytes`,
    `${r.manifests.count} manifest(s), ${r.manifests.bytes} bytes`,
    `${r.uploads.count} upload(s), ${r.uploads.bytes} bytes`,
    ...(r.failures ? [`${r.failures} failure(s)`] : []),
  ].join('; ');

/**
 * Check every hour whether the daily clean-up is due, so a restart neither
 * skips nor repeats it. The first check waits a few minutes for the server
 * to settle.
 */
export function scheduleCleanup(mode: 'on' | 'dry-run') {
  const check = () =>
    scheduledCleanup(mode).catch((err) => console.error('clean-up failed', err));
  setTimeout(check, 5 * 60 * 1000).unref();
  setInterval(check, 60 * 60 * 1000).unref();
}
