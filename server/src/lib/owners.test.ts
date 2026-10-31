import { eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { documents, owners, uploadParts, uploads } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { db } from './db';
import { deleteDocument, purgeTrash, restoreSession } from './documents';
import { assertQuota, listOwners, noteActivity, setQuota, usedBytes } from './owners';

setupSyncServer();

const manifestSize = async (syncId: string) =>
  (await db().select({ size: documents.manifestSize }).from(documents).where(eq(documents.syncId, syncId)))[0].size;

describe('noteActivity (#82)', () => {
  it('adds a new owner, and writes again only after a few minutes', async () => {
    const start = 1_800_000_000_000;
    await noteActivity('alice', start);
    await noteActivity('alice', start + 60_000);
    let [row] = await listOwners();
    expect(row).toMatchObject({ owner: 'alice', firstSeenAt: start, lastSeenAt: start, quotaBytes: null });
    await noteActivity('alice', start + 6 * 60_000);
    [row] = await listOwners();
    expect(row).toMatchObject({ firstSeenAt: start, lastSeenAt: start + 6 * 60_000 });
  });
});

describe('manifest sizes (#82)', () => {
  it('follows a session through push, trash, restore and purge', async () => {
    const pushed = await pushManifest('alice', sessionManifest('s1'));
    const size = await manifestSize('s1');
    expect(size).toBeGreaterThan(0);
    const trashed = await deleteDocument('alice', 'session', 's1', pushed.revision);
    expect(await manifestSize('s1')).toBe(size);
    const restored = await restoreSession('alice', 's1');
    expect(restored.revision).toBe(trashed.revision + 1);
    expect(await manifestSize('s1')).toBe(size);
    await deleteDocument('alice', 'session', 's1', restored.revision);
    await purgeTrash({ owner: 'alice' });
    expect(await manifestSize('s1')).toBeNull();
  });

  it('goes with a document deleted outright', async () => {
    const pushed = await pushManifest('alice', { syncId: 'v1' }, 0, 'voice');
    await deleteDocument('alice', 'voice', 'v1', pushed.revision);
    expect(await manifestSize('v1')).toBeNull();
  });
});

describe('usage (#82)', () => {
  it('counts recordings, manifests in and out of the trash, and upload parts, per owner', async () => {
    const audio = await storeBlob('alice', 'the recording');
    await pushManifest('alice', sessionManifest('s1', { audio }));
    const gone = await pushManifest('alice', sessionManifest('s2'));
    await deleteDocument('alice', 'session', 's2', gone.revision);
    await db().insert(uploads).values({ id: 'u1', owner: 'alice', sha256: 'a'.repeat(64), storeUploadId: 'x' });
    await db().insert(uploadParts).values({ uploadId: 'u1', n: 1, size: 1000, etag: 'e' });
    await storeBlob('bob', 'bob only');
    await noteActivity('alice');
    await noteActivity('bob');

    const expected = audio.size + (await manifestSize('s1'))! + (await manifestSize('s2'))! + 1000;
    expect(await usedBytes('alice')).toBe(expected);
    expect(await usedBytes('bob')).toBe('bob only'.length);
    expect(await usedBytes('nobody')).toBe(0);

    const listed = Object.fromEntries((await listOwners()).map((o) => [o.owner, o]));
    expect(listed.alice).toMatchObject({ sessions: 1, trashed: 1, bytes: expected });
    expect(listed.bob).toMatchObject({ sessions: 0, trashed: 0, bytes: 'bob only'.length });
  });
});

describe('setQuota and assertQuota (#82)', () => {
  it('refuses going over the quota, and only that', async () => {
    await noteActivity('alice');
    await storeBlob('alice', '0123456789');
    await assertQuota('alice', 1_000_000);
    await setQuota('alice', 15);
    await assertQuota('alice', 5);
    await expect(assertQuota('alice', 6)).rejects.toMatchObject({ status: 507, code: 'quota_exceeded' });
    await setQuota('alice', 5);
    await assertQuota('alice', 0);
    await setQuota('alice', null);
    await assertQuota('alice', 1_000_000);
  });

  it('answers 404 for someone not seen', async () => {
    await expect(setQuota('nobody', 5)).rejects.toMatchObject({ status: 404 });
    expect(await db().select().from(owners)).toEqual([]);
  });
});
