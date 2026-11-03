import { eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { documents, talkingPointLists } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { listManifest, pushManifest } from '@/test/sessions';
import { backfillTalkingPointLists } from './backfill';
import { blobKeys, blobStore } from './blob-store';
import { db } from './db';
import { deleteDocument } from './documents';
import { cleanPoint, cleanPoints, listTalkingPointLists, listValues } from './talking-point-index';

setupSyncServer();

const pushList = (owner: string, syncId: string, name: string, points: string[], base = 0) =>
  pushManifest(owner, listManifest(syncId, name, points), base, 'talking-points');

const quiet = () => {};

describe('cleanPoint (#104)', () => {
  it('cleans a point as the app does', () => {
    expect(cleanPoint('  Budget \n for\tQ3  ')).toBe('Budget for Q3');
    expect(cleanPoint('a\u0000b')).toBe('ab');
    expect(cleanPoint('x'.repeat(400))).toHaveLength(300);
    expect(cleanPoint(`${'x'.repeat(299)} y`)).toBe('x'.repeat(299));
    expect(cleanPoints(['One', '  ', 'Two'])).toEqual(['One', 'Two']);
  });

  it('reads what it can of an odd manifest', () => {
    expect(listValues({ name: 7, points: ['A', 3, ' '] }, 42)).toEqual({ name: '', points: ['A'], createdAt: 42, changedAt: 42 });
    expect(listValues(null, 1)).toEqual({ name: '', points: [], createdAt: 1, changedAt: 1 });
  });
});

describe('talking-point list index (#104)', () => {
  it('lists the owner’s lists by name, with their revision', async () => {
    await pushList('alice', 'l2', 'weekly 1:1', ['Hiring', 'Budget']);
    await pushList('alice', 'l1', 'Board review', ['Numbers']);
    await pushList('bob', 'b1', 'Bob’s', ['Secret']);
    const lists = await listTalkingPointLists('alice');
    expect(lists.map((l) => [l.syncId, l.name, l.points, l.revision])).toEqual([
      ['l1', 'Board review', ['Numbers'], 1],
      ['l2', 'weekly 1:1', ['Hiring', 'Budget'], 1],
    ]);
    expect((await listTalkingPointLists('bob')).map((l) => l.syncId)).toEqual(['b1']);
  });

  it('follows a new revision and a delete', async () => {
    await pushList('alice', 'l1', 'Old', ['A']);
    await pushList('alice', 'l1', 'New', ['A', 'B'], 1);
    expect(await listTalkingPointLists('alice')).toMatchObject([{ name: 'New', points: ['A', 'B'], revision: 2 }]);
    await deleteDocument('alice', 'talking-points', 'l1', 2);
    expect(await listTalkingPointLists('alice')).toEqual([]);
    expect(await db().select().from(talkingPointLists)).toEqual([]);
  });

  it('refuses a stale web delete, and a deleted list', async () => {
    await pushList('alice', 'l1', 'List', ['A']);
    await pushList('alice', 'l1', 'List', ['A', 'B'], 1);
    await expect(deleteDocument('alice', 'talking-points', 'l1', 1, null, { strict: true })).rejects.toMatchObject({ status: 409 });
    await deleteDocument('alice', 'talking-points', 'l1', 2, null, { strict: true });
    await expect(deleteDocument('alice', 'talking-points', 'l1', 3, null, { strict: true })).rejects.toMatchObject({ status: 404 });
  });
});

describe('backfillTalkingPointLists (#104)', () => {
  it('indexes lists pushed before the index existed, and resumes', async () => {
    await pushList('alice', 'l1', 'One', ['A']);
    await pushList('alice', 'l2', 'Two', ['B']);
    await pushList('bob', 'b1', 'Bob', ['C']);
    await db().delete(talkingPointLists);
    expect(await backfillTalkingPointLists({ limit: 2, log: quiet })).toEqual({ indexed: 2, skipped: 0 });
    expect(await backfillTalkingPointLists({ log: quiet })).toEqual({ indexed: 1, skipped: 0 });
    expect((await listTalkingPointLists('alice')).map((l) => l.name)).toEqual(['One', 'Two']);
    expect(await backfillTalkingPointLists({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
  });

  it('re-indexes a row read from an older manifest, and skips one it cannot read', async () => {
    await pushList('alice', 'l1', 'New', ['A']);
    await pushList('alice', 'l2', 'Lost', ['B']);
    await db().update(talkingPointLists).set({ manifestHash: 'stale', name: 'Old' }).where(eq(talkingPointLists.syncId, 'l1'));
    await db().delete(talkingPointLists).where(eq(talkingPointLists.syncId, 'l2'));
    const [doc] = await db().select().from(documents).where(eq(documents.syncId, 'l2'));
    await blobStore().delete(blobKeys.manifest('alice', 'talking-points', 'l2', doc.manifestHash!));
    const logged: string[] = [];
    expect(await backfillTalkingPointLists({ log: (m) => logged.push(m) })).toEqual({ indexed: 1, skipped: 1 });
    expect(logged.join()).toMatch(/l2/);
    expect((await listTalkingPointLists('alice')).map((l) => l.name)).toEqual(['New']);
  });
});
