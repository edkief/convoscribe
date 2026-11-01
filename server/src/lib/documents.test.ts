import { describe, expect, it } from 'vitest';
import { createHash } from 'node:crypto';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { deleteDocument, deleteWins, getDocumentMeta, putDocument, pushWins, type DocumentMeta } from './documents';

setupSyncServer();

const current = (over: Partial<DocumentMeta> = {}): DocumentMeta => ({
  kind: 'session',
  syncId: 'id',
  revision: 2,
  seq: 2,
  durationMs: 1000,
  changedAt: 100,
  manifestHash: 'm',
  deleted: false,
  ...over,
});

describe('the conflict rule (#69)', () => {
  it.each([
    ['a longer recording wins', { durationMs: 2000, changedAt: 0, manifestHash: 'a' }, current(), true],
    ['a shorter one loses, however recent', { durationMs: 999, changedAt: 999, manifestHash: 'z' }, current(), false],
    ['equal length: the later change wins', { durationMs: 1000, changedAt: 101, manifestHash: 'a' }, current(), true],
    ['equal length: the earlier change loses', { durationMs: 1000, changedAt: 99, manifestHash: 'z' }, current(), false],
    ['all equal: the higher hash wins', { durationMs: 1000, changedAt: 100, manifestHash: 'n' }, current(), true],
    ['all equal: the lower hash loses', { durationMs: 1000, changedAt: 100, manifestHash: 'l' }, current(), false],
    ['against a delete: longer wins', { durationMs: 1001, changedAt: 0, manifestHash: 'a' }, current({ deleted: true, manifestHash: null }), true],
    ['against a delete: equal loses', { durationMs: 1000, changedAt: 999, manifestHash: 'z' }, current({ deleted: true, manifestHash: null }), false],
  ])('%s', (_, push, cur, wins) => {
    expect(pushWins(push, cur)).toBe(wins);
  });

  it('is antisymmetric, so both devices agree on the winner', () => {
    const a = { durationMs: 1000, changedAt: 100, manifestHash: 'aaa' };
    const b = { durationMs: 1000, changedAt: 100, manifestHash: 'bbb' };
    expect(pushWins(a, current(b))).not.toBe(pushWins(b, current(a)));
  });

  it.each([
    ['a delete beats an equal recording', 1000, current(), true],
    ['a delete beats a shorter recording', 2000, current(), true],
    ['a delete loses to a longer recording', 999, current(), false],
    ['without the deleted length a delete waits', null, current(), false],
    ['deleting a tombstone again is fine', null, current({ deleted: true }), true],
  ])('%s', (_, deleted, cur, wins) => {
    expect(deleteWins(deleted, cur)).toBe(wins);
  });
});

const push = (manifest: unknown, baseRevision: number, over: { durationMs?: number; changedAt?: number } = {}) => {
  const bytes = Buffer.from(JSON.stringify(manifest));
  return {
    manifest: bytes,
    manifestHash: createHash('sha256').update(bytes).digest('hex'),
    baseRevision,
    durationMs: 65_000,
    changedAt: 1_760_000_200_000,
    ...over,
  };
};

describe('a strict push (#84)', () => {
  it('stores a new revision from the current one', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const next = await putDocument('o', 'session', 's1', push(sessionManifest('s1', { title: 'New' }), first.revision), { strict: true });
    expect(next.revision).toBe(first.revision + 1);
  });

  it('refuses a stale revision the conflict rule would let through', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const second = await pushManifest('o', sessionManifest('s1', { title: 'Device' }), first.revision);
    const stale = push(sessionManifest('s1', { title: 'Web' }), first.revision, { durationMs: 999_999 });
    await expect(putDocument('o', 'session', 's1', stale, { strict: true })).rejects.toMatchObject({ status: 409 });
    expect((await getDocumentMeta('o', 'session', 's1'))?.revision).toBe(second.revision);
    // Without strict, the same push wins.
    expect((await putDocument('o', 'session', 's1', stale)).revision).toBe(second.revision + 1);
  });

  it('refuses a stale revision even if it repeats the current manifest', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const same = sessionManifest('s1', { title: 'Device' });
    await pushManifest('o', same, first.revision);
    await expect(putDocument('o', 'session', 's1', push(same, first.revision), { strict: true })).rejects.toMatchObject({ status: 409 });
  });

  it('refuses a deleted or unknown session', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const gone = await deleteDocument('o', 'session', 's1', first.revision);
    const late = push(sessionManifest('s1'), gone.revision, { durationMs: 999_999 });
    await expect(putDocument('o', 'session', 's1', late, { strict: true })).rejects.toMatchObject({ status: 404 });
    await expect(putDocument('o', 'session', 'nope', push(sessionManifest('nope'), 1), { strict: true })).rejects.toMatchObject({ status: 404 });
  });
});

describe('a merging push (#90)', () => {
  it('stores a new revision from the current one', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const next = await putDocument('o', 'session', 's1', push(sessionManifest('s1', { title: 'New' }), first.revision), { merge: true });
    expect(next.revision).toBe(first.revision + 1);
  });

  it('gets a 409 from a stale revision the conflict rule would let through', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const second = await pushManifest('o', sessionManifest('s1', { title: 'Web' }), first.revision);
    const stale = push(sessionManifest('s1', { title: 'Device' }), first.revision, { durationMs: 999_999 });
    await expect(putDocument('o', 'session', 's1', stale, { merge: true })).rejects.toMatchObject({
      status: 409,
      details: { current: { revision: second.revision } },
    });
    expect((await getDocumentMeta('o', 'session', 's1'))?.revision).toBe(second.revision);
    // An app that doesn't merge keeps the whole-session rule.
    expect((await putDocument('o', 'session', 's1', stale)).revision).toBe(second.revision + 1);
  });

  it('succeeds when a stale push repeats the current manifest', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const same = sessionManifest('s1', { title: 'Same' });
    const second = await pushManifest('o', same, first.revision);
    expect((await putDocument('o', 'session', 's1', push(same, first.revision), { merge: true })).revision).toBe(second.revision);
  });

  it('leaves a tombstone to the conflict rule', async () => {
    const first = await pushManifest('o', sessionManifest('s1'));
    const gone = await deleteDocument('o', 'session', 's1', first.revision, first.durationMs);
    const shorter = push(sessionManifest('s1'), first.revision, { durationMs: first.durationMs });
    await expect(putDocument('o', 'session', 's1', shorter, { merge: true })).rejects.toMatchObject({ status: 409 });
    const longer = push(sessionManifest('s1'), first.revision, { durationMs: first.durationMs + 1 });
    expect((await putDocument('o', 'session', 's1', longer, { merge: true })).revision).toBe(gone.revision + 1);
  });
});
