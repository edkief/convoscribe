import { sql } from 'drizzle-orm';
import { fetch as undiciFetch } from 'undici';
import { agent, upstreamOf, type Service } from './ai-proxy';
import { blobStore } from './blob-store';
import { db } from './db';
import { env } from './env';

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

/** A GET of `url` that counts only a 2xx answer as reached; the body is not read. */
async function probe(url: string, key?: string): Promise<void> {
  const res = await undiciFetch(url, {
    headers: { accept: 'application/json', ...(key ? { authorization: `Bearer ${key}` } : {}) },
    signal: AbortSignal.timeout(CHECK_TIMEOUT_MS),
    redirect: 'manual',
    dispatcher: agent(),
  });
  await res.body?.cancel();
  if (!res.ok) throw new Error(`answered ${res.status}${res.status === 401 || res.status === 403 ? ', check its key' : ''}`);
}

/** Where each upstream is asked whether it is up: a cheap GET it already serves (#145). */
const PROBE_PATH: Record<Service, string> = { llm: '/models', asr: '/models', decision: '/health' };

const UPSTREAM_NAME: Record<Service, string> = { llm: 'language model', asr: 'transcription', decision: 'decision service' };

/** An upstream the server is set up with: whether it answered, and the model it is set to use. */
export type UpstreamReachability = Reachability & { model: string | null };

/** Whether the upstream for `service` answers, or null when the server has none (#145). */
export async function checkUpstream(service: Service): Promise<UpstreamReachability | null> {
  const upstream = upstreamOf(service);
  if (!upstream) return null;
  const state = await check(UPSTREAM_NAME[service], () => probe(`${upstream.url}${PROBE_PATH[service]}`, upstream.key));
  return { ...state, model: upstream.model ?? null };
}

/** Whether authz-proxy serves the keys sign-ins are checked against, or null in api-key mode (#145). */
export async function checkAuthzProxy(): Promise<Reachability | null> {
  const config = env();
  if (config.AUTH_MODE !== 'authz-proxy') return null;
  return check('authz-proxy', () => probe(`${config.AUTHZ_PROXY_URL}/.well-known/jwks.json`));
}

/** Each dependency the status page asks after, by the name its row fetches it with. */
export const STATUS_CHECKS = ['postgres', 's3', 'authz-proxy', 'llm', 'asr', 'decision'] as const;
export type StatusCheck = (typeof STATUS_CHECKS)[number];

export const isStatusCheck = (name: string): name is StatusCheck => (STATUS_CHECKS as readonly string[]).includes(name);

/** Run one check by name: null when the server is not set up with that dependency. */
export function runCheck(name: StatusCheck): Promise<Reachability | UpstreamReachability | null> {
  switch (name) {
    case 'postgres':
      return checkPostgres();
    case 's3':
      return checkStore();
    case 'authz-proxy':
      return checkAuthzProxy();
    default:
      return checkUpstream(name);
  }
}

/**
 * What the status page can show before any check answers, from the server's
 * set-up alone: whether each dependency is configured, and the model an
 * upstream is set to use.
 */
export type CheckSetup = { name: StatusCheck; configured: boolean; model: string | null };

export function checkSetup(): CheckSetup[] {
  const upstream = (service: Service): CheckSetup => {
    const u = upstreamOf(service);
    return { name: service, configured: !!u, model: u?.model ?? null };
  };
  return [
    { name: 'postgres', configured: true, model: null },
    { name: 's3', configured: true, model: null },
    { name: 'authz-proxy', configured: env().AUTH_MODE === 'authz-proxy', model: null },
    upstream('llm'),
    upstream('asr'),
    upstream('decision'),
  ];
}

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

/**
 * What the status page shows of the server's dependencies and storage (#82):
 * Postgres and S3, and the upstreams it offers and authz-proxy (#145), each
 * null when the server is not set up with it.
 */
export type ServerStatus = {
  postgres: Reachability;
  s3: Reachability;
  authzProxy: Reachability | null;
  llm: UpstreamReachability | null;
  asr: UpstreamReachability | null;
  decision: UpstreamReachability | null;
  storage: StorageBreakdown | null;
};

/** Check every dependency at once, and add the storage figures when Postgres can give them. */
export async function serverStatus(): Promise<ServerStatus> {
  const [postgres, s3, authzProxy, llm, asr, decision] = await Promise.all([
    checkPostgres(),
    checkStore(),
    checkAuthzProxy(),
    checkUpstream('llm'),
    checkUpstream('asr'),
    checkUpstream('decision'),
  ]);
  const storage = postgres.ok ? await storageBreakdown().catch(() => null) : null;
  return { postgres, s3, authzProxy, llm, asr, decision, storage };
}
