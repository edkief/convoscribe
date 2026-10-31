import { and, eq, sql } from 'drizzle-orm';
import { describe, expect, it, vi } from 'vitest';
import { sessionIndex, sessionText } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { backfillSessionIndex } from './backfill';
import { db } from './db';
import { deleteDocument, purgeTrash, restoreSession } from './documents';
import { SEARCH_TEXT_VERSION, sessionTextRows } from './session-search';
import { sessionView, spoken } from './transcript-export';

setupSyncServer();

const quiet = () => {};

const rows = (owner: string, syncId: string) =>
  db()
    .select()
    .from(sessionText)
    .where(and(eq(sessionText.owner, owner), eq(sessionText.syncId, syncId)));

const textVersion = async (owner: string, syncId: string) =>
  (await db().select().from(sessionIndex).where(and(eq(sessionIndex.owner, owner), eq(sessionIndex.syncId, syncId))))[0]?.textVersion;

const summary = (content: string) => ({ kind: 'SUMMARY', role: 'ASSISTANT', content, createdAt: 1 });

describe('session text (#83)', () => {
  it('holds the title, speakers, summaries and lines of a manifest', () => {
    const found = sessionTextRows(
      sessionManifest('s1', { messages: [summary('Old summary'), summary('## Plan\nNew **summary**'), { kind: 'QA', role: 'ASSISTANT', content: 'An answer' }] }),
    );
    expect(found.map((r) => [r.source, r.position, r.text, r.startMs, r.speaker])).toEqual([
      ['title', 0, 'Session s1', null, null],
      ['speaker', 0, 'Alice', null, null],
      ['speaker', 1, 'Bob', null, null],
      ['summary', 0, 'Plan New summary', null, null],
      ['summary', 1, 'Old summary', null, null],
      ['line', 0, 'Hello there.', 0, 'Alice'],
      ['line', 1, 'Hi, how are you?', 4000, 'Bob'],
    ]);
  });

  it('never throws on an odd manifest', () => {
    for (const manifest of [null, 42, 'x', [], { title: 7, lines: {}, speakers: 'a', messages: 3 }]) {
      expect(sessionTextRows(manifest)).toEqual([]);
    }
  });

  it('numbers lines as the session page does', () => {
    const manifest = sessionManifest('s1', {
      lines: [
        { startMs: 9000, endMs: 9500, text: 'Third' },
        { startMs: 1000, endMs: 1500, text: '   ' },
        { startMs: 0, endMs: 500, text: 'First' },
        { startMs: 5000, endMs: 5500, text: 'Second' },
      ],
    });
    const lines = sessionTextRows(manifest).filter((r) => r.source === 'line');
    const page = spoken(sessionView(manifest));
    expect(lines.map((r) => page[r.position].text)).toEqual(['First', 'Second', 'Third']);
  });

  it('is written with a push and replaced by the next one', async () => {
    await pushManifest('alice', sessionManifest('s1', { title: 'Budget' }));
    expect(await textVersion('alice', 's1')).toBe(SEARCH_TEXT_VERSION);
    expect((await rows('alice', 's1')).map((r) => r.text)).toContain('Budget');
    await pushManifest('alice', sessionManifest('s1', { title: 'Hiring', lines: [] }), 1);
    expect((await rows('alice', 's1')).map((r) => r.text).sort()).toEqual(['Alice', 'Bob', 'Hiring']);
  });

  it('skips text with no word in it, and fractional times', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: [{ startMs: 1.6, text: 'Fine' }, { startMs: 2, text: '...' }] }));
    const lines = (await rows('alice', 's1')).filter((r) => r.source === 'line');
    expect(lines.map((r) => [r.text, r.startMs])).toEqual([['Fine', 2]]);
  });

  it('stores a very long transcript rather than failing the push', async () => {
    const long = Array.from({ length: 400_000 }, (_, i) => `w${i}`).join(' ');
    expect(long.length).toBeGreaterThan(2_500_000);
    const many = Array.from({ length: 20_000 }, (_, i) => ({ startMs: i + 1, text: `line${i} words` }));
    await pushManifest('alice', sessionManifest('s1', { lines: [{ startMs: 0, text: long }, ...many] }));
    expect(await textVersion('alice', 's1')).toBe(SEARCH_TEXT_VERSION);
    const stored = (await rows('alice', 's1')).filter((r) => r.source === 'line' && r.position === 0);
    expect(stored.length).toBeGreaterThan(600);
    expect(stored.sort((a, b) => a.chunk - b.chunk).map((r) => r.text).join(' ')).toBe(long);
    const [{ count }] = await db().select({ count: sql<number>`count(*)::int` }).from(sessionText).where(eq(sessionText.source, 'line'));
    expect(count).toBe(stored.length + 20_000);
  }, 60_000);

  it('never fails a push it could not index, and the backfill tries again', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(quiet);
    await db().execute(sql`alter table session_text rename to session_text_away`);
    await pushManifest('alice', sessionManifest('s1', { title: 'Budget' }));
    expect(warn).toHaveBeenCalledWith(expect.stringMatching(/s1 of alice/));
    expect(await textVersion('alice', 's1')).toBe(0);
    await db().execute(sql`alter table session_text_away rename to session_text`);
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 1, skipped: 0 });
    expect(await textVersion('alice', 's1')).toBe(SEARCH_TEXT_VERSION);
    expect((await rows('alice', 's1')).map((r) => r.text)).toContain('Budget');
    warn.mockRestore();
  });

  it('goes when a session goes to the trash, returns on restore, and stays gone after a purge', async () => {
    await pushManifest('alice', sessionManifest('s1'));
    await pushManifest('alice', sessionManifest('s2'));
    await deleteDocument('alice', 'session', 's1', 1);
    expect(await rows('alice', 's1')).toEqual([]);
    await restoreSession('alice', 's1');
    expect(await rows('alice', 's1')).not.toEqual([]);
    await deleteDocument('alice', 'session', 's2', 1);
    await purgeTrash({ owner: 'alice' });
    expect(await rows('alice', 's2')).toEqual([]);
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
    expect(await rows('alice', 's2')).toEqual([]);
  });

  it('reaches sessions indexed before search existed, without a push, and resumes', async () => {
    for (const id of ['s1', 's2', 's3']) await pushManifest('alice', sessionManifest(id));
    await db().delete(sessionText);
    await db().update(sessionIndex).set({ textVersion: 0 });
    expect(await backfillSessionIndex({ limit: 2, log: quiet })).toEqual({ indexed: 2, skipped: 0 });
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 1, skipped: 0 });
    for (const id of ['s1', 's2', 's3']) expect(await rows('alice', id)).not.toEqual([]);
    expect(await backfillSessionIndex({ log: quiet })).toEqual({ indexed: 0, skipped: 0 });
  });
});
