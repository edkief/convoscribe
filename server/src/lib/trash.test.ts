import { and, eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { documentBlobs, documents } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { db } from './db';
import { deleteDocument, getDocumentMeta, listChanges, purgeTrash, restoreSession, TRASH_MS } from './documents';
import { HttpError } from './http';
import { getSessionIndex, listSessions } from './session-index';
import { countTrash, deleteSessionFromWeb, listTrash } from './trash';

setupSyncServer();

const refs = (owner: string, syncId: string) =>
  db()
    .select({ sha256: documentBlobs.sha256 })
    .from(documentBlobs)
    .where(and(eq(documentBlobs.owner, owner), eq(documentBlobs.syncId, syncId)));

/** A session with a recording, pushed and then deleted by a device. */
async function trashed(owner = 'alice', syncId = 's1') {
  const audio = await storeBlob(owner, `audio of ${syncId}`);
  const pushed = await pushManifest(owner, sessionManifest(syncId, { audio }));
  const deleted = await deleteDocument(owner, 'session', syncId, pushed.revision, 65_000);
  return { audio, pushed, deleted };
}

/** Move a session's time in the trash back by `ms`. */
const age = (owner: string, syncId: string, ms: number) =>
  db()
    .update(documents)
    .set({ trashedAt: new Date(Date.now() - ms) })
    .where(and(eq(documents.owner, owner), eq(documents.syncId, syncId)));

const status = async (promise: Promise<unknown>) => {
  try {
    await promise;
    return 200;
  } catch (err) {
    if (err instanceof HttpError) return err.status;
    throw err;
  }
};

describe('the trash (#81)', () => {
  it('keeps what a device deleted, while devices see the usual tombstone', async () => {
    const { audio, pushed, deleted } = await trashed();
    expect(deleted).toMatchObject({ deleted: true, manifestHash: null, revision: pushed.revision + 1 });
    expect(await refs('alice', 's1')).toEqual([{ sha256: audio.sha256 }]);
    expect((await listSessions('alice')).sessions).toEqual([]);
    const items = await listTrash('alice');
    expect(items).toMatchObject([{ syncId: 's1', title: 'Session s1', durationMs: 65_000 }]);
    expect(items[0].expiresAt - items[0].trashedAt).toBe(TRASH_MS);
    expect(await countTrash('alice')).toBe(1);
    expect(await listTrash('bob')).toEqual([]);
  });

  it('deletes other kinds outright', async () => {
    const pushed = await pushManifest('alice', { name: 'Ann', audio: await storeBlob('alice', 'clip') }, 0, 'voice', 'v1');
    await deleteDocument('alice', 'voice', 'v1', pushed.revision);
    expect(await refs('alice', 'v1')).toEqual([]);
    expect(await listTrash('alice')).toEqual([]);
  });

  it('restores a session as a new revision every device downloads', async () => {
    const { audio, pushed, deleted } = await trashed();
    const restored = await restoreSession('alice', 's1');
    expect(restored).toMatchObject({ deleted: false, manifestHash: pushed.manifestHash, revision: deleted.revision + 1 });
    expect(restored.seq).toBeGreaterThan(deleted.seq);
    const feed = await listChanges('alice', deleted.seq, 10);
    expect(feed.changes).toEqual([restored]);
    expect(await refs('alice', 's1')).toEqual([{ sha256: audio.sha256 }]);
    expect((await listSessions('alice')).sessions.map((s) => s.syncId)).toEqual(['s1']);
    expect(await listTrash('alice')).toEqual([]);
    expect(await status(restoreSession('alice', 's1'))).toBe(404);
  });

  it('cannot restore another owner’s session, a live one or an unknown one', async () => {
    await trashed('alice', 's1');
    await pushManifest('alice', sessionManifest('live'));
    expect(await status(restoreSession('bob', 's1'))).toBe(404);
    expect(await status(restoreSession('alice', 'live'))).toBe(404);
    expect(await status(restoreSession('alice', 'nope'))).toBe(404);
  });

  it('cannot restore after 30 days, even before clean-up purged it', async () => {
    await trashed();
    await age('alice', 's1', TRASH_MS + 1000);
    expect(await listTrash('alice')).toEqual([]);
    expect(await countTrash('alice')).toBe(0);
    expect(await status(restoreSession('alice', 's1'))).toBe(404);
  });

  it('cannot restore a session deleted before the trash existed', async () => {
    await trashed();
    await db().update(documents).set({ trashedAt: null, trashedManifestHash: null });
    expect(await status(restoreSession('alice', 's1'))).toBe(404);
  });

  it('empties an owner’s trash, keeping the tombstone for the feed', async () => {
    await trashed('alice', 's1');
    await trashed('alice', 's2');
    await trashed('bob', 'b1');
    expect(await purgeTrash({ owner: 'alice' })).toBe(2);
    expect(await refs('alice', 's1')).toEqual([]);
    expect(await getSessionIndex('alice', 's1')).toBeNull();
    expect(await getDocumentMeta('alice', 'session', 's1')).toMatchObject({ deleted: true });
    expect(await status(restoreSession('alice', 's1'))).toBe(404);
    expect(await listTrash('bob')).toHaveLength(1);
  });

  it('purges only sessions trashed before a time', async () => {
    await trashed('alice', 'old');
    await trashed('alice', 'new');
    await age('alice', 'old', TRASH_MS + 1000);
    expect(await purgeTrash({ before: new Date(Date.now() - TRASH_MS) })).toBe(1);
    expect(await refs('alice', 'old')).toEqual([]);
    expect(await refs('alice', 'new')).toHaveLength(1);
  });

  it('gives way to a longer recording pushed over the tombstone', async () => {
    const { pushed } = await trashed();
    const longer = await storeBlob('alice', 'longer audio');
    await pushManifest('alice', sessionManifest('s1', { audio: longer, durationMs: 90_000 }), pushed.revision);
    const [row] = await db().select().from(documents).where(eq(documents.syncId, 's1'));
    expect(row).toMatchObject({ deleted: false, trashedAt: null, trashedManifestHash: null });
    expect(await refs('alice', 's1')).toEqual([{ sha256: longer.sha256 }]);
  });

  it('sends a restored session back to the trash when an offline device’s delete arrives', async () => {
    const audio = await storeBlob('alice', 'audio');
    const pushed = await pushManifest('alice', sessionManifest('s1', { audio }));
    await deleteDocument('alice', 'session', 's1', pushed.revision, 65_000);
    await restoreSession('alice', 's1');
    // Another device deleted the same version offline: its stale delete still wins (#69).
    await deleteDocument('alice', 'session', 's1', pushed.revision, 65_000);
    expect(await listTrash('alice')).toMatchObject([{ syncId: 's1' }]);
    expect((await restoreSession('alice', 's1')).deleted).toBe(false);
  });
});

describe('deleteSessionFromWeb (#81)', () => {
  it('trashes the session and deletes its vectors', async () => {
    const pushed = await pushManifest('alice', sessionManifest('s1'));
    await pushManifest('alice', { syncId: 's1', vectors: [] }, 0, 'vectors', 's1');
    await deleteSessionFromWeb('alice', 's1', pushed.revision);
    expect(await listTrash('alice')).toHaveLength(1);
    expect(await getDocumentMeta('alice', 'vectors', 's1')).toMatchObject({ deleted: true });
    await restoreSession('alice', 's1');
    expect(await getDocumentMeta('alice', 'vectors', 's1')).toMatchObject({ deleted: true });
  });

  it('refuses a page older than the current revision', async () => {
    const first = await pushManifest('alice', sessionManifest('s1'));
    await pushManifest('alice', sessionManifest('s1', { title: 'Edited on a phone' }), first.revision);
    expect(await status(deleteSessionFromWeb('alice', 's1', first.revision))).toBe(409);
    expect(await listTrash('alice')).toEqual([]);
  });

  it('cannot delete another owner’s session', async () => {
    const pushed = await pushManifest('alice', sessionManifest('s1'));
    expect(await status(deleteSessionFromWeb('bob', 's1', pushed.revision))).toBe(404);
    expect(await getDocumentMeta('alice', 'session', 's1')).toMatchObject({ deleted: false });
  });
});
