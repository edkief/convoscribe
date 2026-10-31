import { sql } from 'drizzle-orm';
import { blobStore } from './blob-store';
import { db } from './db';

/** How long a dependency has to answer before it counts as unreachable. */
const CHECK_TIMEOUT_MS = 5000;

/** Whether a dependency answered. Why not goes to the server's log, never into a response. */
export type Reachability = { ok: boolean; latencyMs: number };

async function check(name: string, probe: () => Promise<unknown>): Promise<Reachability> {
  const start = Date.now();
  let timer: NodeJS.Timeout | undefined;
  try {
    await Promise.race([
      probe(),
      new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`no answer in ${CHECK_TIMEOUT_MS} ms`)), CHECK_TIMEOUT_MS);
      }),
    ]);
    return { ok: true, latencyMs: Date.now() - start };
  } catch (err) {
    console.warn(`status: ${name} unreachable: ${err instanceof Error ? err.message : String(err)}`);
    return { ok: false, latencyMs: Date.now() - start };
  } finally {
    clearTimeout(timer);
  }
}

export const checkPostgres = () => check('Postgres', () => db().execute(sql`select 1`));

export const checkStore = () => check('S3', () => blobStore().check());

/** Bytes stored across all users, from Postgres (#79, decision 9). */
export type StorageBreakdown = {
  /** Recordings and manifests of live documents; a recording also in the trash counts here. */
  live: number;
  /** Recordings and manifests only trashed sessions use. */
  trash: number;
  /** Recordings nothing refers to, which clean-up removes after the grace period. */
  unreferenced: number;
  /** Parts of uploads not finished. */
  uploads: number;
  total: number;
};

/** What every user stores, by what it is for. Old manifests are not tracked in Postgres, so not included. */
export async function storageBreakdown(): Promise<StorageBreakdown> {
  const [row] = await db()
    .select({
      blobsLive: sql<string>`(select coalesce(sum(b.size), 0) from blobs b where ${usedBy('not d.deleted')})`,
      blobsTrash: sql<string>`(select coalesce(sum(b.size), 0) from blobs b where not ${usedBy('not d.deleted')} and ${usedBy('d.deleted')})`,
      unreferenced: sql<string>`(select coalesce(sum(b.size), 0) from blobs b where not ${usedBy('true')})`,
      manifestsLive: sql<string>`(select coalesce(sum(d.manifest_size), 0) from documents d where not d.deleted)`,
      manifestsTrash: sql<string>`(select coalesce(sum(d.manifest_size), 0) from documents d where d.deleted)`,
      uploads: sql<string>`(select coalesce(sum(p.size), 0) from upload_parts p)`,
    })
    .from(sql`(select 1) as one`);
  const live = Number(row.blobsLive) + Number(row.manifestsLive);
  const trash = Number(row.blobsTrash) + Number(row.manifestsTrash);
  const unreferenced = Number(row.unreferenced);
  const uploads = Number(row.uploads);
  return { live, trash, unreferenced, uploads, total: live + trash + unreferenced + uploads };
}

const usedBy = (condition: 'not d.deleted' | 'd.deleted' | 'true') => sql.raw(`exists (
  select 1 from document_blobs r
  join documents d on d.owner = r.owner and d.kind = r.kind and d.sync_id = r.sync_id
  where r.owner = b.owner and r.sha256 = b.sha256 and ${condition}
)`);

/** What the status page shows of the server's dependencies and storage (#82). */
export type ServerStatus = { postgres: Reachability; s3: Reachability; storage: StorageBreakdown | null };

/** Check Postgres and S3, and add the storage figures when Postgres can give them. */
export async function serverStatus(): Promise<ServerStatus> {
  const [postgres, s3] = await Promise.all([checkPostgres(), checkStore()]);
  return { postgres, s3, storage: postgres.ok ? await storageBreakdown().catch(() => null) : null };
}
