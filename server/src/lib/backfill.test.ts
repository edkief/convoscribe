import { and, eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { documents, sessionIndex } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { backfillSessionIndex } from './backfill';
import { blobKeys, blobStore } from './blob-store';
import { db } from './db';
import { getSessionIndex, listSessions } from './session-index';

setupSyncServer();

/** Sessions as a server before #80 left them: pushed, with no index rows. */
async function pushedBeforeIndex(owner: string, ids: string[]) {
  for (const id of ids) await pushManifest(owner, sessionManifest(id, { title: `Before ${id}` }));
  await db().delete(sessionIndex);
}

const quiet = () => {};

describe('backfillSessionIndex (#80)', () => {
  it('indexes sessions pushed before the index existed', async () => {
    await pushedBeforeIndex('alice', ['s1', 's2']);
    await pushedBeforeIndex('bob', ['b1']);
    expect((await listSessions('alice')).sessions).toEqual([]);
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 3, skipped: 0 });
    expect((await listSessions('alice')).sessions.map((s) => s.title).sort()).toEqual(['Before s1', 'Before s2']);
    expect((await listSessions('bob')).sessions.map((s) => s.syncId)).toEqual(['b1']);
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
  });

  it('resumes where an interrupted run stopped', async () => {
    await pushedBeforeIndex('alice', ['s1', 's2', 's3']);
    expect(await backfillSessionIndex({ limit: 2, log: quiet })).toEqual({ indexed: 2, skipped: 0 });
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 1, skipped: 0 });
    expect((await listSessions('alice')).sessions).toHaveLength(3);
  });

  it('re-indexes a row read from an older manifest', async () => {
    await pushManifest('alice', sessionManifest('s1', { title: 'Old' }));
    await pushManifest('alice', sessionManifest('s1', { title: 'New' }), 1);
    await db()
      .update(sessionIndex)
      .set({ manifestHash: 'stale', title: 'Old' })
      .where(and(eq(sessionIndex.owner, 'alice'), eq(sessionIndex.syncId, 's1')));
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 1, skipped: 0 });
    expect((await getSessionIndex('alice', 's1'))?.title).toBe('New');
  });

  it('skips a manifest it cannot read, and carries on', async () => {
    await pushedBeforeIndex('alice', ['s1', 's2']);
    const [doc] = await db().select().from(documents).where(eq(documents.syncId, 's1'));
    await blobStore().delete(blobKeys.manifest('alice', 'session', 's1', doc.manifestHash!));
    const logged: string[] = [];
    expect(await backfillSessionIndex({ log: (m) => logged.push(m) })).toEqual({ indexed: 1, skipped: 1 });
    expect(logged.join()).toMatch(/s1/);
    expect((await listSessions('alice')).sessions.map((s) => s.syncId)).toEqual(['s2']);
  });

  it('leaves out deleted sessions', async () => {
    await pushedBeforeIndex('alice', ['s1']);
    await db().update(documents).set({ deleted: true, manifestHash: null });
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
  });

  it('never overwrites a push made while it read the manifest', async () => {
    await pushedBeforeIndex('alice', ['s1']);
    const store = blobStore();
    const realGet = store.get.bind(store);
    // A device pushes a newer version just as the backfill fetches the old manifest.
    store.get = async (key, range) => {
      store.get = realGet;
      const object = await realGet(key, range);
      await pushManifest('alice', sessionManifest('s1', { title: 'Pushed meanwhile' }), 1);
      return object;
    };
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
    expect((await getSessionIndex('alice', 's1'))?.title).toBe('Pushed meanwhile');
  });
});
