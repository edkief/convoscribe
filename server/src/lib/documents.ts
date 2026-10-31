import { and, asc, eq, gt, sql } from 'drizzle-orm';
import { createHash } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import { documentBlobs, documents } from '@/db/schema';
import { blobKeys, blobStore } from './blob-store';
import { missingBlobs } from './blobs';
import { db } from './db';
import { HttpError, type Kind } from './http';
import { upsertSessionIndex } from './session-index';

/** What the index knows about a document's current revision. */
export type DocumentMeta = {
  kind: string;
  syncId: string;
  revision: number;
  seq: number;
  durationMs: number;
  changedAt: number;
  manifestHash: string | null;
  deleted: boolean;
};

/** A device's push: the manifest bytes and what it says about them (#67, decision 5). */
export type Push = {
  manifest: Uint8Array;
  manifestHash: string;
  baseRevision: number;
  durationMs: number;
  changedAt: number;
};

const meta = (row: typeof documents.$inferSelect): DocumentMeta => ({
  kind: row.kind,
  syncId: row.syncId,
  revision: row.revision,
  seq: row.seq,
  durationMs: row.durationMs,
  changedAt: row.changedAt,
  manifestHash: row.manifestHash,
  deleted: row.deleted,
});

const conflict = (current: DocumentMeta | null) => new HttpError(409, 'conflict', { current });

/**
 * The conflict rule (#67, decision 5; #69), for a push whose base revision is
 * no longer the current one: the longer recording wins, then the later
 * `changedAt`, then the higher manifest hash, so every device settles on the
 * same version. Against a tombstone, whose `durationMs` is the length of the
 * version that was deleted, a push wins only with a longer recording.
 */
export function pushWins(push: Pick<Push, 'durationMs' | 'changedAt' | 'manifestHash'>, current: DocumentMeta): boolean {
  if (current.deleted) return push.durationMs > current.durationMs;
  if (push.durationMs !== current.durationMs) return push.durationMs > current.durationMs;
  if (push.changedAt !== current.changedAt) return push.changedAt > current.changedAt;
  return push.manifestHash > (current.manifestHash ?? '');
}

/**
 * Whether a delete of a version `deletedDurationMs` long beats the current
 * one it never saw: it does unless the current recording is longer, so a
 * continued recording is never thrown away. Without the deleted length (an
 * older app) the delete waits, as before #69.
 */
export function deleteWins(deletedDurationMs: number | null, current: DocumentMeta): boolean {
  if (current.deleted) return true;
  return deletedDurationMs !== null && current.durationMs <= deletedDurationMs;
}

/**
 * Every recording a manifest refers to: any object with a hex `sha256` and a
 * numeric `size` (a `FileRef`). Only this is read from the manifest, so the
 * server need not track the app's schema.
 */
export function blobRefs(manifest: unknown): string[] {
  const found = new Set<string>();
  const walk = (value: unknown) => {
    if (Array.isArray(value)) value.forEach(walk);
    else if (value && typeof value === 'object') {
      const record = value as Record<string, unknown>;
      if (typeof record.sha256 === 'string' && /^[0-9a-f]{64}$/.test(record.sha256) && typeof record.size === 'number') {
        found.add(record.sha256);
      }
      Object.values(record).forEach(walk);
    }
  };
  walk(manifest);
  return [...found];
}

/** Writes for one owner run one at a time, so `seq` order is commit order for the feed. */
export const lockOwner = (tx: Pick<ReturnType<typeof db>, 'execute'>, owner: string) =>
  tx.execute(sql`select pg_advisory_xact_lock(hashtext(${owner}))`);

/**
 * Store a new revision of a document if the device's `baseRevision` is the
 * current one (compare-and-swap), or if it is stale but wins the conflict
 * rule ([pushWins]); otherwise 409 with the current version, which the device
 * then downloads. A push that repeats the current manifest succeeds without a
 * new revision, so a device can retry after losing the response. A session's
 * index row for the web app is written in the same transaction (#80).
 */
export async function putDocument(owner: string, kind: Kind, syncId: string, push: Push): Promise<DocumentMeta> {
  const actual = createHash('sha256').update(push.manifest).digest('hex');
  if (actual !== push.manifestHash) throw new HttpError(400, 'hash_mismatch', { actual });
  let parsed: unknown;
  try {
    parsed = JSON.parse(Buffer.from(push.manifest).toString('utf8'));
  } catch {
    throw new HttpError(400, 'invalid_manifest');
  }
  const refs = blobRefs(parsed);
  const missing = await missingBlobs(owner, refs);
  if (missing.length > 0) throw new HttpError(422, 'missing_blobs', { missing });

  // Content-addressed, so concurrent pushes never overwrite each other's bytes.
  await blobStore().put(blobKeys.manifest(owner, kind, syncId, push.manifestHash), gzipSync(push.manifest));

  return db().transaction(async (tx) => {
    await lockOwner(tx, owner);
    const where = and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId));
    const [current] = await tx.select().from(documents).where(where);
    const values = {
      durationMs: push.durationMs,
      changedAt: push.changedAt,
      manifestHash: push.manifestHash,
      deleted: false,
      seq: sql`nextval('change_seq')`,
      updatedAt: new Date(),
    };
    let row: typeof documents.$inferSelect;
    if (!current) {
      // Also taken when the server lost what the device last saw: nothing competes with it.
      [row] = await tx
        .insert(documents)
        .values({ owner, kind, syncId, revision: 1, ...values })
        .returning();
    } else if (!current.deleted && current.manifestHash === push.manifestHash) {
      return meta(current);
    } else if (current.revision === push.baseRevision || pushWins(push, meta(current))) {
      [row] = await tx
        .update(documents)
        .set({ revision: current.revision + 1, ...values })
        .where(where)
        .returning();
    } else {
      throw conflict(meta(current));
    }
    await tx.delete(documentBlobs).where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.kind, kind), eq(documentBlobs.syncId, syncId)));
    if (refs.length > 0) {
      await tx.insert(documentBlobs).values(refs.map((sha256) => ({ owner, kind, syncId, sha256 })));
    }
    if (kind === 'session') await upsertSessionIndex(tx, owner, syncId, push.manifestHash, parsed, push.changedAt);
    return meta(row);
  });
}

/**
 * Delete a document, leaving a tombstone in the feed so other devices delete
 * it too. From a stale `baseRevision` the delete still goes ahead when it
 * wins ([deleteWins]) against the version it never saw, which the device was
 * `deletedDurationMs` long; otherwise 409. Deleting a tombstone again
 * succeeds. The tombstone keeps the length of what it replaced, for
 * [pushWins].
 */
export async function deleteDocument(
  owner: string,
  kind: Kind,
  syncId: string,
  baseRevision: number,
  deletedDurationMs: number | null = null,
): Promise<DocumentMeta> {
  return db().transaction(async (tx) => {
    await lockOwner(tx, owner);
    const where = and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId));
    const [current] = await tx.select().from(documents).where(where);
    if (!current) throw new HttpError(404, 'not_found');
    if (current.deleted) return meta(current);
    if (current.revision !== baseRevision && !deleteWins(deletedDurationMs, meta(current))) throw conflict(meta(current));
    const [row] = await tx
      .update(documents)
      .set({
        revision: current.revision + 1,
        manifestHash: null,
        deleted: true,
        changedAt: Date.now(),
        seq: sql`nextval('change_seq')`,
        updatedAt: new Date(),
      })
      .where(where)
      .returning();
    await tx.delete(documentBlobs).where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.kind, kind), eq(documentBlobs.syncId, syncId)));
    return meta(row);
  });
}

/** A document's index row, or null. */
export async function getDocumentMeta(owner: string, kind: Kind, syncId: string): Promise<DocumentMeta | null> {
  const [row] = await db()
    .select()
    .from(documents)
    .where(and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId)));
  return row ? meta(row) : null;
}

/** The manifest bytes of a document's current revision. */
export async function readManifest(owner: string, kind: Kind, syncId: string, manifestHash: string): Promise<Uint8Array> {
  const object = await blobStore().get(blobKeys.manifest(owner, kind, syncId, manifestHash));
  if (!object) throw new HttpError(404, 'not_found');
  return gunzipSync(Buffer.from(await new Response(object.body).arrayBuffer()));
}

/** Documents written after `since`, in write order, at most `limit` of them. */
export async function listChanges(owner: string, since: number, limit: number) {
  const rows = await db()
    .select()
    .from(documents)
    .where(and(eq(documents.owner, owner), gt(documents.seq, since)))
    .orderBy(asc(documents.seq))
    .limit(limit + 1);
  const page = rows.slice(0, limit).map(meta);
  return { changes: page, next: page.at(-1)?.seq ?? since, more: rows.length > limit };
}
