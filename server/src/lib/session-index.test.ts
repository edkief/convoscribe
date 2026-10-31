import { describe, expect, it } from 'vitest';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { deleteDocument } from './documents';
import {
  countSessions,
  decodeCursor,
  encodeCursor,
  getSessionIndex,
  indexValues,
  listSessions,
  listTags,
  storageUsed,
} from './session-index';

setupSyncServer();

const DAY = 86_400_000;
const T0 = 1_760_000_000_000;

describe('indexValues', () => {
  it('reads what the library shows from a manifest', () => {
    const audio = { sha256: 'a'.repeat(64), size: 10 };
    expect(indexValues(sessionManifest('s1', { tags: ['Work', 'work', 'Work', ' '], audio }), 0)).toEqual({
      title: 'Session s1',
      createdAt: T0,
      durationMs: 65_000,
      language: 'en',
      status: 'DIARIZED',
      tags: ['Work', 'work'],
      speakerCount: 2,
      speakers: ['Alice', 'Bob'],
      preview: 'Hello there. Hi, how are you?',
      openActions: 0,
      lineCount: 2,
      audioSha256: audio.sha256,
      audioSize: 10,
      cleanAudioSha256: null,
      cleanAudioSize: null,
    });
  });

  it('previews the latest summary as plain text, and counts open action items', () => {
    const values = indexValues(
      sessionManifest('s1', {
        messages: [
          { kind: 'SUMMARY', role: 'ASSISTANT', content: 'Old summary', createdAt: 1 },
          { kind: 'QA', role: 'ASSISTANT', content: 'An answer', createdAt: 2 },
          {
            kind: 'SUMMARY',
            role: 'ASSISTANT',
            content: '## Overview\nThe **team** agreed on [the plan](https://x.y).\n\n- Ship it\n\n| A | B |\n|---|---|\n| 1 | 2 |',
            createdAt: 3,
          },
        ],
        actionItems: [
          { text: 'Open', createdAt: 1 },
          { text: 'Done', done: true, createdAt: 1 },
          { text: 'Gone', deleted: true, createdAt: 1 },
        ],
      }),
      0,
    );
    expect(values.preview).toBe('The team agreed on the plan. Ship it A B 1 2');
    expect(values.openActions).toBe(1);
  });

  it('turns anything missing or unknown into null, never throwing', () => {
    for (const manifest of [null, 42, 'x', [], { title: 7, createdAt: 'yesterday', tags: 'a', lines: {}, audio: { sha256: 'no' } }]) {
      const values = indexValues(manifest, 123);
      expect(values.title).toBeNull();
      expect(values.createdAt).toBe(123);
      expect(values.tags).toEqual([]);
      expect(values.lineCount).toBeNull();
      expect(values.audioSha256).toBeNull();
    }
  });
});

describe('the session index (#80)', () => {
  it('is written by every session push, and not for other kinds', async () => {
    await pushManifest('alice', sessionManifest('s1', { tags: ['Work'] }));
    expect((await getSessionIndex('alice', 's1'))?.tags).toEqual(['Work']);
    const meta = await pushManifest('alice', sessionManifest('s1', { title: 'Renamed', tags: [] }), 1);
    const row = await getSessionIndex('alice', 's1');
    expect(row?.title).toBe('Renamed');
    expect(row?.manifestHash).toBe(meta.manifestHash);
    await pushManifest('alice', { name: 'Voice' }, 0, 'voice', 'v1');
    expect(await getSessionIndex('alice', 'v1')).toBeNull();
  });

  it('accepts a push whose manifest it cannot read anything from', async () => {
    await pushManifest('alice', { durationMs: 5 }, 0, 'session', 'odd');
    expect((await listSessions('alice')).sessions.map((s) => [s.syncId, s.title])).toEqual([['odd', null]]);
  });

  it('accepts text Postgres cannot store as is', async () => {
    await pushManifest('alice', sessionManifest('nul', { title: 'A\u0000B', tags: ['x\u0000y', '\u0000'] }));
    const row = await getSessionIndex('alice', 'nul');
    expect([row?.title, row?.tags]).toEqual(['AB', ['xy']]);
  });

  it('lists only the owner’s live sessions, newest first', async () => {
    await pushManifest('alice', sessionManifest('old', { createdAt: T0 }));
    await pushManifest('alice', sessionManifest('new', { createdAt: T0 + DAY }));
    await pushManifest('alice', sessionManifest('gone', { createdAt: T0 + 2 * DAY }));
    await deleteDocument('alice', 'session', 'gone', 1);
    await pushManifest('bob', sessionManifest('bobs', { createdAt: T0 + 3 * DAY }));
    expect((await listSessions('alice')).sessions.map((s) => s.syncId)).toEqual(['new', 'old']);
    expect((await listSessions('bob')).sessions.map((s) => s.syncId)).toEqual(['bobs']);
  });

  it('filters by tag and by date, and pages', async () => {
    for (let i = 0; i < 5; i++) {
      await pushManifest('alice', sessionManifest(`s${i}`, { createdAt: T0 + i * DAY, tags: i % 2 ? ['Odd'] : ['Even', 'Work'] }));
    }
    await pushManifest('bob', sessionManifest('b', { tags: ['Secret'] }));
    const ids = async (filter: Parameters<typeof listSessions>[1]) =>
      (await listSessions('alice', filter)).sessions.map((s) => s.syncId);
    expect(await ids({ tag: 'Odd' })).toEqual(['s3', 's1']);
    expect(await ids({ tag: 'Secret' })).toEqual([]);
    expect(await ids({ from: T0 + DAY, to: T0 + 3 * DAY })).toEqual(['s2', 's1']);

    const first = await listSessions('alice', { limit: 2 });
    expect(first.sessions.map((s) => s.syncId)).toEqual(['s4', 's3']);
    const cursor = decodeCursor(encodeCursor(first.next!));
    const second = await listSessions('alice', { limit: 2, after: cursor });
    expect(second.sessions.map((s) => s.syncId)).toEqual(['s2', 's1']);
    const third = await listSessions('alice', { limit: 2, after: second.next });
    expect(third.sessions.map((s) => s.syncId)).toEqual(['s0']);
    expect(third.next).toBeUndefined();

    expect(await listTags('alice')).toEqual([
      { tag: 'Even', count: 3 },
      { tag: 'Odd', count: 2 },
      { tag: 'Work', count: 3 },
    ]);
    expect(await countSessions('alice')).toBe(5);
  });

  it('keeps sessions recorded at the same moment apart across pages', async () => {
    for (const id of ['a', 'b', 'c']) await pushManifest('alice', sessionManifest(id));
    const first = await listSessions('alice', { limit: 2 });
    const second = await listSessions('alice', { limit: 2, after: first.next });
    expect([...first.sessions, ...second.sessions].map((s) => s.syncId)).toEqual(['c', 'b', 'a']);
  });

  it('refuses a malformed cursor', () => {
    expect(decodeCursor('nope')).toBeUndefined();
    expect(decodeCursor(undefined)).toBeUndefined();
    expect(decodeCursor('12.a/b')).toBeUndefined();
  });

  it('adds up the owner’s stored recordings', async () => {
    expect(await storageUsed('alice')).toBe(0);
    await storeBlob('alice', 'x'.repeat(100));
    await storeBlob('alice', 'y'.repeat(50));
    await storeBlob('bob', 'z'.repeat(1000));
    expect(await storageUsed('alice')).toBe(150);
  });
});
