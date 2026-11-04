import { and, asc, eq, sql } from 'drizzle-orm';
import { documents, talkingPointLists } from '@/db/schema';
import { db, type Db } from './db';
import { cleanPoint, cleanPoints } from './talking-point-text';

export { POINT_MAX, cleanPoint, cleanPoints } from './talking-point-text';

/** A talking-point list as the web app shows it (#104). */
export type TalkingPointList = {
  syncId: string;
  /** The revision an edit must name. */
  revision: number;
  name: string;
  points: string[];
  createdAt: number;
  changedAt: number;
};

const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};

/**
 * What the index keeps of a list manifest (`TalkingPointListManifest` in the
 * app). Tolerant like the session index: a field in a shape this server does
 * not know reads as empty, so a push never fails on it.
 */
export function listValues(manifest: unknown, changedAt: number) {
  const m = record(manifest);
  const points = Array.isArray(m.points) ? cleanPoints(m.points.filter((p): p is string => typeof p === 'string')) : [];
  const createdAt = typeof m.createdAt === 'number' && Number.isSafeInteger(m.createdAt) ? m.createdAt : changedAt;
  return { name: typeof m.name === 'string' ? cleanPoint(m.name) : '', points, createdAt, changedAt };
}

/** Write a list's index row from its manifest; runs inside the push's transaction. */
export async function upsertTalkingPointList(
  tx: Pick<Db, 'insert'>,
  owner: string,
  syncId: string,
  manifestHash: string,
  manifest: unknown,
  changedAt: number,
) {
  const values = { ...listValues(manifest, changedAt), manifestHash };
  await tx
    .insert(talkingPointLists)
    .values({ owner, syncId, ...values })
    .onConflictDoUpdate({ target: [talkingPointLists.owner, talkingPointLists.syncId], set: values });
}

/** Remove a list's index row; runs inside the delete's transaction. */
export async function deleteTalkingPointList(tx: Pick<Db, 'delete'>, owner: string, syncId: string) {
  await tx.delete(talkingPointLists).where(and(eq(talkingPointLists.owner, owner), eq(talkingPointLists.syncId, syncId)));
}

/** `owner`'s live lists, by name, from Postgres alone. */
export async function listTalkingPointLists(owner: string): Promise<TalkingPointList[]> {
  return db()
    .select({
      syncId: talkingPointLists.syncId,
      revision: documents.revision,
      name: talkingPointLists.name,
      points: talkingPointLists.points,
      createdAt: talkingPointLists.createdAt,
      changedAt: talkingPointLists.changedAt,
    })
    .from(talkingPointLists)
    .innerJoin(
      documents,
      and(
        eq(documents.owner, talkingPointLists.owner),
        eq(documents.kind, 'talking-points'),
        eq(documents.syncId, talkingPointLists.syncId),
        eq(documents.deleted, false),
      ),
    )
    .where(eq(talkingPointLists.owner, owner))
    .orderBy(sql`lower(${talkingPointLists.name})`, asc(talkingPointLists.syncId));
}
