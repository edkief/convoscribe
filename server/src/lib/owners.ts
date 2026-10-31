import { desc, eq, sql, type SQL } from 'drizzle-orm';
import { owners } from '@/db/schema';
import { db } from './db';
import { HttpError } from './http';

/** How often a user's last activity is written. */
const ACTIVITY_EVERY_MS = 5 * 60 * 1000;

const lastNoted = new Map<string, number>();

/**
 * Record that `owner` is active (#79, decision 8), at most every few minutes
 * per owner. Never fails or holds up the request for long, and a database
 * that is down is asked again only when the next write is due.
 */
export async function noteActivity(owner: string, now = Date.now()): Promise<void> {
  const last = lastNoted.get(owner);
  if (last !== undefined && now - last < ACTIVITY_EVERY_MS) return;
  lastNoted.set(owner, now);
  const seen = new Date(now);
  try {
    await db()
      .insert(owners)
      .values({ owner, firstSeenAt: seen, lastSeenAt: seen })
      .onConflictDoUpdate({ target: owners.owner, set: { lastSeenAt: seen } });
  } catch (err) {
    console.warn(`could not note activity of ${owner}: ${err instanceof Error ? err.message : String(err)}`);
  }
}

/** Forget when owners were last noted (tests only). */
export function resetActivity() {
  lastNoted.clear();
}

/**
 * The bytes an owner stores: recordings, current and trashed manifests, and
 * parts of unfinished uploads. Old manifests, not tracked in Postgres, are
 * left out until clean-up removes them. `owner` is an SQL expression, spelled
 * with its table when it is a column: a column interpolated into a selected
 * expression loses its table name, and would compare with the subquery's own.
 */
const usageOf = (owner: SQL) => sql<string>`(
  (select coalesce(sum(b.size), 0) from blobs b where b.owner = ${owner})
  + (select coalesce(sum(d.manifest_size), 0) from documents d where d.owner = ${owner})
  + (select coalesce(sum(p.size), 0) from upload_parts p join uploads u on u.id = p.upload_id where u.owner = ${owner})
)`;

/** What an owner stores, in bytes. */
export async function usedBytes(owner: string): Promise<number> {
  const [row] = await db().select({ bytes: usageOf(sql`${owner}`) }).from(sql`(select 1) as one`);
  return Number(row?.bytes ?? 0);
}

/** A user as the admin pages list them: counts, bytes and times, nothing from a manifest. */
export type OwnerUsage = {
  owner: string;
  sessions: number;
  trashed: number;
  bytes: number;
  quotaBytes: number | null;
  firstSeenAt: number;
  lastSeenAt: number;
};

/** Everyone seen by this server, the most recently active first. */
export async function listOwners(): Promise<OwnerUsage[]> {
  const rows = await db()
    .select({
      owner: owners.owner,
      sessions: sql<number>`(select count(*)::int from documents d where d.owner = owners.owner and d.kind = 'session' and not d.deleted)`,
      trashed: sql<number>`(select count(*)::int from documents d where d.owner = owners.owner and d.kind = 'session' and d.deleted and d.trashed_at is not null)`,
      bytes: usageOf(sql`owners.owner`),
      quotaBytes: owners.quotaBytes,
      firstSeenAt: owners.firstSeenAt,
      lastSeenAt: owners.lastSeenAt,
    })
    .from(owners)
    .orderBy(desc(owners.lastSeenAt), owners.owner);
  return rows.map((row) => ({
    ...row,
    bytes: Number(row.bytes),
    firstSeenAt: row.firstSeenAt.getTime(),
    lastSeenAt: row.lastSeenAt.getTime(),
  }));
}

/** Set how much an owner may store; null is unlimited. 404 for someone this server has not seen. */
export async function setQuota(owner: string, quotaBytes: number | null): Promise<void> {
  const [row] = await db().update(owners).set({ quotaBytes }).where(eq(owners.owner, owner)).returning({ owner: owners.owner });
  if (!row) throw new HttpError(404, 'unknown_owner');
}

/**
 * Refuse, with `507 quota_exceeded`, storing `incoming` more bytes when it
 * would take the owner over their quota (#79, decision 8). Checked only for
 * uploads, so edits and deletes always work. Uploads running at once can pass
 * the quota by about a part each: it is a limit on use, not a guarantee.
 */
export async function assertQuota(owner: string, incoming: number): Promise<void> {
  if (incoming <= 0) return;
  const [row] = await db().select({ quotaBytes: owners.quotaBytes }).from(owners).where(eq(owners.owner, owner));
  if (row?.quotaBytes == null) return;
  if ((await usedBytes(owner)) + incoming > row.quotaBytes) throw new HttpError(507, 'quota_exceeded');
}
