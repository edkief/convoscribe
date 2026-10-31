import { and, asc, eq, isNotNull, isNull, ne, or, sql } from 'drizzle-orm';
import { documents, sessionIndex } from '@/db/schema';
import { db } from './db';
import { lockOwner, readManifest } from './documents';
import { upsertSessionIndex } from './session-index';

/**
 * Index every live session whose index row is missing or was read from
 * another manifest: those pushed before the web app existed (#80), or
 * after a row was lost. One manifest at a time, since a manifest can
 * inflate to 128 MB. Each row is written under the owner's lock, and only
 * if the session still has the manifest that was read, so a push made
 * meanwhile (which indexes itself) is never overwritten. It keeps no
 * state: run again, it picks up whatever is still missing. A manifest that
 * cannot be read is logged and skipped. At most `limit` sessions are
 * indexed.
 */
export async function backfillSessionIndex(
  options: { limit?: number; log?: (message: string) => void } = {},
): Promise<{ indexed: number; skipped: number }> {
  const log = options.log ?? ((message: string) => console.warn(message));
  let indexed = 0;
  let skipped = 0;
  let after: { owner: string; syncId: string } | undefined;
  while (options.limit === undefined || indexed < options.limit) {
    const [doc] = await db()
      .select({ owner: documents.owner, syncId: documents.syncId, manifestHash: documents.manifestHash, changedAt: documents.changedAt })
      .from(documents)
      .leftJoin(sessionIndex, and(eq(sessionIndex.owner, documents.owner), eq(sessionIndex.syncId, documents.syncId)))
      .where(
        and(
          eq(documents.kind, 'session'),
          eq(documents.deleted, false),
          isNotNull(documents.manifestHash),
          or(isNull(sessionIndex.manifestHash), ne(sessionIndex.manifestHash, documents.manifestHash)),
          after ? sql`(${documents.owner}, ${documents.syncId}) > (${after.owner}, ${after.syncId})` : undefined,
        ),
      )
      .orderBy(asc(documents.owner), asc(documents.syncId))
      .limit(1);
    if (!doc) break;
    after = doc;
    const manifestHash = doc.manifestHash!;
    try {
      const manifest: unknown = JSON.parse(
        Buffer.from(await readManifest(doc.owner, 'session', doc.syncId, manifestHash)).toString('utf8'),
      );
      const wrote = await db().transaction(async (tx) => {
        await lockOwner(tx, doc.owner);
        const [current] = await tx
          .select({ manifestHash: documents.manifestHash, deleted: documents.deleted })
          .from(documents)
          .where(and(eq(documents.owner, doc.owner), eq(documents.kind, 'session'), eq(documents.syncId, doc.syncId)));
        if (current?.deleted || current?.manifestHash !== manifestHash) return false;
        await upsertSessionIndex(tx, doc.owner, doc.syncId, manifestHash, manifest, doc.changedAt);
        return true;
      });
      if (wrote) indexed++;
    } catch (err) {
      skipped++;
      log(`session index: skipped ${doc.syncId} of ${doc.owner}: ${err instanceof Error ? err.message : String(err)}`);
    }
  }
  return { indexed, skipped };
}
