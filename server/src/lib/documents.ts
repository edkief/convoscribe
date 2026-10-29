import { and, asc, eq, gt, sql } from 'drizzle-orm';
import { createHash } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import { documentBlobs, documents } from '@/db/schema';
import { blobKeys, blobStore } from './blob-store';
import { missingBlobs } from './blobs';
import { db } from './db';
import { HttpError, type Kind } from './http';

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
const lockOwner = (tx: Pick<ReturnType<typeof db>, 'execute'>, owner: string) =>
  tx.execute(sql`select pg_advisory_xact_lock(hashtext(${owner}))`);

/**
 * Store a new revision of a document if the device's `baseRevision` is the
 * current one (compare-and-swap); otherwise 409 with the current version. A
 * push that repeats the current manifest succeeds without a new revision, so
 * a device can retry after losing the response.
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
    } else if (current.revision === push.baseRevision) {
      [row] = await tx
        .update(documents)
        .set({ revision: current.revision + 1, ...values })
        .where(where)
        .returning();
    } else if (!current.deleted && current.manifestHash === push.manifestHash) {
      return meta(current);
    } else {
      throw conflict(meta(current));
    }
    await tx.delete(documentBlobs).where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.kind, kind), eq(documentBlobs.syncId, syncId)));
    if (refs.length > 0) {
      await tx.insert(documentBlobs).values(refs.map((sha256) => ({ owner, kind, syncId, sha256 })));
    }
    return meta(row);
  });
}

/**
 * Delete a document, leaving a tombstone in the feed so other devices delete
 * it too; compare-and-swap as for [putDocument]. Deleting a tombstone again
 * succeeds.
 */
export async function deleteDocument(owner: string, kind: Kind, syncId: string, baseRevision: number): Promise<DocumentMeta> {
  return db().transaction(async (tx) => {
    await lockOwner(tx, owner);
    const where = and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId));
    const [current] = await tx.select().from(documents).where(where);
    if (!current) throw new HttpError(404, 'not_found');
    if (current.deleted) return meta(current);
    if (current.revision !== baseRevision) throw conflict(meta(current));
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
