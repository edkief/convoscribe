import { and, eq, sql } from 'drizzle-orm';
import { describe, expect, it, vi } from 'vitest';
import { sessionIndex, sessionText } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest } from '@/test/sessions';
import { backfillSessionIndex } from './backfill';
import { db } from './db';
import { deleteDocument, purgeTrash, restoreSession } from './documents';
import { searchSessions, SEARCH_TEXT_VERSION, sessionTextRows } from './session-search';
import type { Segment } from './search-text';
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

const marked = (segments: Segment[]) => segments.map((s) => (s.match ? `[${s.text}]` : s.text)).join('');

const ids = async (owner: string, query: string) => (await searchSessions(owner, query)).sessions.map((s) => s.syncId);

const said = (...lines: string[]) => lines.map((text, i) => ({ startMs: i * 1000, endMs: i * 1000 + 500, text, speakerIndex: 0 }));

describe('searchSessions (#83)', () => {
  it('finds words ignoring case and accents, by prefix, with the matched words marked', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: said('On parle de la réunion demain.') }));
    await pushManifest('alice', sessionManifest('s2', { lines: said('Nothing like it.') }));
    for (const query of ['reunion', 'RÉUNION', 'reun', 're\u0301union']) expect(await ids('alice', query)).toEqual(['s1']);
    const [session] = (await searchSessions('alice', 'reun')).sessions;
    expect(session.lines.map((l) => [l.line, l.startMs, l.speaker, marked(l.text)])).toEqual([
      [0, 0, 'Alice', 'On parle de la [réunion] demain.'],
    ]);
  });

  it('needs every word in the same place', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: said('The budget is set.', 'The team agreed.') }));
    await pushManifest('alice', sessionManifest('s2', { lines: said('The team budget is set.') }));
    expect(await ids('alice', 'team budget')).toEqual(['s2']);
  });

  it('finds titles, speakers and every summary, saying where', async () => {
    await pushManifest(
      'alice',
      sessionManifest('s1', {
        title: 'Quarterly plan',
        speakers: [{ index: 0, label: 'Élodie' }],
        messages: [summary('Old zebra notes'), summary('## Decisions\nNew zebra notes')],
        lines: said('Nothing.'),
      }),
    );
    const search = async (query: string) => (await searchSessions('alice', query)).sessions[0];
    expect(marked((await search('quarter')).titleMatch!)).toBe('[Quarterly] plan');
    expect((await search('elodie')).speakers.map(marked)).toEqual(['[Élodie]']);
    expect((await search('zebra')).summaries.map((s) => [s.position, marked(s.text)])).toEqual([
      [0, 'Decisions New [zebra] notes'],
      [1, 'Old [zebra] notes'],
    ]);
    expect((await search('decisions')).summaries.map((s) => s.position)).toEqual([0]);
  });

  it("never returns another user's sessions, even with the same id", async () => {
    await pushManifest('alice', sessionManifest('same', { lines: said('Alpha secret.') }));
    await pushManifest('bob', sessionManifest('same', { lines: said('Beta secret.') }));
    await pushManifest('bob', sessionManifest('other', { lines: said('Alpha elsewhere.') }));
    const alice = await searchSessions('alice', 'secret');
    expect(alice.sessions.flatMap((s) => s.lines.map((l) => marked(l.text)))).toEqual(['Alpha [secret].']);
    expect(await ids('alice', 'beta')).toEqual([]);
    expect(await ids('alice', 'elsewhere')).toEqual([]);
    expect(await ids('carol', 'secret')).toEqual([]);
  });

  it('leaves out trashed sessions until restored', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: said('Findable words.') }));
    await deleteDocument('alice', 'session', 's1', 1);
    expect(await ids('alice', 'findable')).toEqual([]);
    await restoreSession('alice', 's1');
    expect(await ids('alice', 'findable')).toEqual(['s1']);
  });

  it('finds a word past any size limit of a very long transcript', async () => {
    const long = `${Array.from({ length: 300_000 }, (_, i) => `w${i}`).join(' ')} needle`;
    await pushManifest('alice', sessionManifest('s1', { lines: [{ startMs: 0, text: long }] }));
    const [session] = (await searchSessions('alice', 'needle')).sessions;
    expect(session.lines).toHaveLength(1);
    expect(marked(session.lines[0].text)).toMatch(/\[needle\]$/);
  }, 60_000);

  it('treats operators as nothing, and finds nothing without a word', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: said('Plain words here.') }));
    for (const query of ['', '   ', `' & | ! :*`, '<->']) expect(await searchSessions('alice', query)).toEqual({ sessions: [] });
    expect(await ids('alice', `plain' | 'nothing`)).toEqual([]);
    expect(await ids('alice', `plain:* & !words`)).toEqual(['s1']);
  });

  it('pages newest first', async () => {
    for (const [i, id] of ['s1', 's2', 's3'].entries()) {
      await pushManifest('alice', sessionManifest(id, { createdAt: 1000 + i, lines: said('Common word.') }));
    }
    const first = await searchSessions('alice', 'common', { limit: 2 });
    expect(first.sessions.map((s) => s.syncId)).toEqual(['s3', 's2']);
    const second = await searchSessions('alice', 'common', { limit: 2, after: first.next });
    expect(second.sessions.map((s) => s.syncId)).toEqual(['s1']);
    expect(second.next).toBeUndefined();
  });

  it('returns matching lines in reading order, up to a cap, and counts the rest', async () => {
    await pushManifest('alice', sessionManifest('s1', { lines: said(...Array.from({ length: 60 }, (_, i) => `Repeat ${i}.`)) }));
    const [session] = (await searchSessions('alice', 'repeat')).sessions;
    expect(session.lines.map((l) => l.line)).toEqual(Array.from({ length: 50 }, (_, i) => i));
    expect(session.moreLines).toBe(10);
  });
});
