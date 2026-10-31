import { and, count, desc, eq, gte } from 'drizzle-orm';
import { documents, sessionIndex } from '@/db/schema';
import { db } from './db';
import { deleteDocument, getDocumentMeta, TRASH_MS, trashedWhere } from './documents';
import { HttpError } from './http';

/** A session in the trash, as the web app lists it (#81). */
export type TrashItem = {
  syncId: string;
  title: string | null;
  createdAt: number | null;
  durationMs: number | null;
  /** When it was deleted, ms since epoch. */
  trashedAt: number;
  /** When it leaves the trash for good, ms since epoch. */
  expiresAt: number;
};

/** An owner's restorable sessions, the most recently deleted first. */
export async function listTrash(owner: string, now = Date.now()): Promise<TrashItem[]> {
  const rows = await db()
    .select({
      syncId: documents.syncId,
      trashedAt: documents.trashedAt,
      title: sessionIndex.title,
      createdAt: sessionIndex.createdAt,
      durationMs: sessionIndex.durationMs,
    })
    .from(documents)
    .leftJoin(sessionIndex, and(eq(sessionIndex.owner, documents.owner), eq(sessionIndex.syncId, documents.syncId)))
    .where(and(trashedWhere({ owner }), restorable(now)))
    .orderBy(desc(documents.trashedAt), desc(documents.syncId));
  return rows.map((row) => {
    const trashedAt = row.trashedAt!.getTime();
    return { ...row, trashedAt, expiresAt: trashedAt + TRASH_MS };
  });
}

// Past TRASH_DAYS a session waits for clean-up to purge it, but is no longer restorable.
const restorable = (now: number) => gte(documents.trashedAt, new Date(now - TRASH_MS));

/** How many sessions an owner has in the trash. */
export async function countTrash(owner: string, now = Date.now()): Promise<number> {
  const [row] = await db()
    .select({ count: count() })
    .from(documents)
    .where(and(trashedWhere({ owner }), restorable(now)));
  return row?.count ?? 0;
}

/**
 * Delete a session from the web app (#81): to the trash like a device's
 * delete, but only from the revision the page showed, so a session changed
 * on a device since is never deleted unseen (409). Its semantic vectors are
 * deleted too, for devices to drop; a restore does not bring them back,
 * since devices index a session again by themselves.
 */
export async function deleteSessionFromWeb(owner: string, syncId: string, baseRevision: number) {
  const current = await getDocumentMeta(owner, 'session', syncId);
  if (!current || current.deleted) throw new HttpError(404, 'not_found');
  const meta = await deleteDocument(owner, 'session', syncId, baseRevision);
  const vectors = await getDocumentMeta(owner, 'vectors', syncId);
  if (vectors && !vectors.deleted) {
    try {
      await deleteDocument(owner, 'vectors', syncId, vectors.revision);
    } catch (err) {
      // A device pushed new vectors meanwhile; devices drop vectors of a deleted session anyway.
      if (!(err instanceof HttpError && err.status === 409)) throw err;
    }
  }
  return meta;
}
