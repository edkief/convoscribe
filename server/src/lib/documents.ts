import { and, asc, eq, gt, isNotNull, lt, sql } from 'drizzle-orm';
import { createHash } from 'node:crypto';
import { gunzipSync, gzipSync } from 'node:zlib';
import { documentBlobs, documents, sessionIndex } from '@/db/schema';
import { blobKeys, blobStore } from './blob-store';
import { missingBlobs } from './blobs';
import { db, type Db } from './db';
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

/** How long a deleted session stays in the trash, restorable from the web (#81). */
export const TRASH_DAYS = 30;
export const TRASH_MS = TRASH_DAYS * 24 * 60 * 60 * 1000;

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
 *
 * The recordings it refers to are checked, and the manifest stored, under
 * the owner's lock, which clean-up also takes before removing anything
 * (#81): neither can disappear between the check and the commit.
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
  const gzipped = gzipSync(push.manifest);

  return db().transaction(async (tx) => {
    await lockOwner(tx, owner);
    const missing = await missingBlobs(owner, refs, tx);
    if (missing.length > 0) throw new HttpError(422, 'missing_blobs', { missing });
    const where = and(eq(documents.owner, owner), eq(documents.kind, kind), eq(documents.syncId, syncId));
    const [current] = await tx.select().from(documents).where(where);
    if (current && !current.deleted && current.manifestHash === push.manifestHash) return meta(current);
    // Content-addressed, so concurrent pushes never overwrite each other's bytes.
    await blobStore().put(blobKeys.manifest(owner, kind, syncId, push.manifestHash), gzipped);
    const values = {
      durationMs: push.durationMs,
      changedAt: push.changedAt,
      manifestHash: push.manifestHash,
      manifestSize: gzipped.length,
      deleted: false,
      // A push that wins over a trashed session replaces what the trash kept.
      trashedAt: null,
      trashedManifestHash: null,
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
 *
 * A session goes to the trash (#81): its tombstone keeps the deleted
 * manifest's hash and its recordings' references, so clean-up leaves them
 * and [restoreSession] can bring it back for [TRASH_DAYS]. Devices see the
 * same tombstone either way. Other kinds are deleted outright.
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
    const trash = kind === 'session' && current.manifestHash !== null;
    const [row] = await tx
      .update(documents)
      .set({
        revision: current.revision + 1,
        manifestHash: null,
        deleted: true,
        changedAt: Date.now(),
        seq: sql`nextval('change_seq')`,
        updatedAt: new Date(),
        trashedAt: trash ? new Date() : null,
        trashedManifestHash: trash ? current.manifestHash : null,
        manifestSize: trash ? current.manifestSize : null,
      })
      .where(where)
      .returning();
    if (!trash) await tx.delete(documentBlobs).where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.kind, kind), eq(documentBlobs.syncId, syncId)));
    return meta(row);
  });
}

const inTrash = (row: typeof documents.$inferSelect | undefined, now: number) =>
  row !== undefined &&
  row.deleted &&
  row.trashedManifestHash !== null &&
  row.trashedAt !== null &&
  row.trashedAt.getTime() > now - TRASH_MS;

/**
 * Bring a session back from the trash (#81) as a new revision of the
 * manifest it had when deleted, so every device downloads it again with
 * its recordings. 404 `not_in_trash` once it was purged, has been in the
 * trash for [TRASH_DAYS], or was deleted before the trash existed.
 */
export async function restoreSession(owner: string, syncId: string): Promise<DocumentMeta> {
  const where = and(eq(documents.owner, owner), eq(documents.kind, 'session'), eq(documents.syncId, syncId));
  const [before] = await db().select().from(documents).where(where);
  if (!inTrash(before, Date.now())) throw new HttpError(404, 'not_in_trash');
  const manifestHash = before.trashedManifestHash!;
  // Read before taking the lock: a manifest can inflate to 128 MB.
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(Buffer.from(await readManifest(owner, 'session', syncId, manifestHash)).toString('utf8'));
  } catch (err) {
    if (err instanceof HttpError) throw new HttpError(404, 'not_in_trash');
    // Stored manifests were valid JSON when pushed; restore it with an empty index row rather than fail.
  }
  return db().transaction(async (tx) => {
    await lockOwner(tx, owner);
    const [current] = await tx.select().from(documents).where(where);
    if (!inTrash(current, Date.now()) || current.trashedManifestHash !== manifestHash) {
      throw new HttpError(404, 'not_in_trash');
    }
    const changedAt = Date.now();
    const [row] = await tx
      .update(documents)
      .set({
        revision: current.revision + 1,
        manifestHash,
        deleted: false,
        changedAt,
        seq: sql`nextval('change_seq')`,
        updatedAt: new Date(),
        trashedAt: null,
        trashedManifestHash: null,
      })
      .where(where)
      .returning();
    await upsertSessionIndex(tx, owner, syncId, manifestHash, parsed, changedAt);
    return meta(row);
  });
}

/**
 * Empty sessions out of the trash (#81): an owner's whole trash, or every
 * owner's sessions trashed before `before`. A purged tombstone stays in the
 * feed but keeps nothing, so clean-up can remove its manifest and the
 * recordings no other session uses, and it can no longer be restored.
 * Returns how many sessions were purged.
 */
export async function purgeTrash(filter: { owner?: string; before?: Date }): Promise<number> {
  const candidates = await db()
    .select({ owner: documents.owner, syncId: documents.syncId })
    .from(documents)
    .where(trashedWhere(filter));
  const byOwner = new Map<string, string[]>();
  for (const c of candidates) byOwner.set(c.owner, [...(byOwner.get(c.owner) ?? []), c.syncId]);
  let purged = 0;
  for (const [owner, syncIds] of byOwner) {
    purged += await db().transaction(async (tx) => {
      await lockOwner(tx, owner);
      let count = 0;
      for (const syncId of syncIds) {
        const [row] = await tx
          .update(documents)
          .set({ trashedAt: null, trashedManifestHash: null, manifestSize: null, updatedAt: new Date() })
          .where(and(eq(documents.syncId, syncId), trashedWhere({ owner, before: filter.before })))
          .returning({ syncId: documents.syncId });
        if (!row) continue;
        await purgeKept(tx, owner, syncId);
        count++;
      }
      return count;
    });
  }
  return purged;
}

/** Trashed sessions, of one owner and trashed before a time when given. */
export const trashedWhere = (filter: { owner?: string; before?: Date }) =>
  and(
    eq(documents.kind, 'session'),
    eq(documents.deleted, true),
    isNotNull(documents.trashedAt),
    filter.owner !== undefined ? eq(documents.owner, filter.owner) : undefined,
    filter.before !== undefined ? lt(documents.trashedAt, filter.before) : undefined,
  );

async function purgeKept(tx: Pick<Db, 'delete'>, owner: string, syncId: string) {
  await tx
    .delete(documentBlobs)
    .where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.kind, 'session'), eq(documentBlobs.syncId, syncId)));
  await tx.delete(sessionIndex).where(and(eq(sessionIndex.owner, owner), eq(sessionIndex.syncId, syncId)));
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
