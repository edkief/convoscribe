import { describe, expect, it } from 'vitest';
import { deleteWins, pushWins, type DocumentMeta } from './documents';

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
