import { and, eq } from 'drizzle-orm';
import { describe, expect, it } from 'vitest';
import { documentBlobs, documents, sessionIndex } from '@/db/schema';
import { setupSyncServer } from '@/test/http';
import { pushManifest, sessionManifest, storeBlob } from '@/test/sessions';
import { db } from './db';
import { deleteDocument, getDocumentMeta, readManifest } from './documents';
import { editSession, parseEdit, type SessionEdit } from './session-edit';
import { searchSessions } from './session-search';
import { sessionView } from './transcript-export';

setupSyncServer();

const OWNER = 'alice';
const SYNC_ID = 's1';

const stored = async (owner = OWNER, syncId = SYNC_ID) => {
  const meta = (await getDocumentMeta(owner, 'session', syncId))!;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return JSON.parse(Buffer.from(await readManifest(owner, 'session', syncId, meta.manifestHash!)).toString()) as any;
};

const item = (over: Record<string, unknown> = {}) => ({
  text: 'Send it',
  extractedText: 'Send it',
  owner: 'Ann',
  dueDate: '2026-11-06',
  atMs: 500,
  done: false,
  edited: false,
  deleted: false,
  createdAt: 1,
  ...over,
});

async function edited(edit: SessionEdit, over: Record<string, unknown> = {}) {
  const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, over));
  const meta = await editSession(OWNER, SYNC_ID, pushed.revision, edit);
  return { pushed, meta, manifest: await stored() };
}

describe('editing a session from the web (#84)', () => {
  describe('title', () => {
    it('takes the app’s rules: trimmed, capped at 100, the user’s, nothing to undo', async () => {
      const long = `  ${'x'.repeat(99)} y and more  `;
      const { meta, pushed, manifest } = await edited({ kind: 'title', title: long }, { titleSource: 'auto', previousTitle: 'Old' });
      expect(meta.revision).toBe(pushed.revision + 1);
      expect(manifest.title).toBe('x'.repeat(99));
      expect(manifest.titleSource).toBe('user');
      expect(manifest.previousTitle).toBeNull();
    });

    it('ignores a blank one', async () => {
      const { meta, pushed, manifest } = await edited({ kind: 'title', title: '  \n ' });
      expect(meta.revision).toBe(pushed.revision);
      expect(manifest.title).toBe(`Session ${SYNC_ID}`);
    });

    it('makes a chosen title of an automatic one, even with the same words', async () => {
      const { manifest } = await edited({ kind: 'title', title: 'Same' }, { title: 'Same', titleSource: 'auto' });
      expect(manifest.titleSource).toBe('user');
    });
  });

  describe('tags', () => {
    it('cleans them, drops repeats in any case and sorts by name', async () => {
      const long = 'y'.repeat(40);
      const { manifest } = await edited({ kind: 'tags', tags: ['  work  stuff ', 'Alpha', 'alpha', ' ', long, 'zed'] });
      expect(manifest.tags).toEqual(['Alpha', 'work stuff', 'y'.repeat(30), 'zed']);
    });

    it('changes nothing for the same tags', async () => {
      const { meta, pushed } = await edited({ kind: 'tags', tags: ['b', 'a'] }, { tags: ['a', 'b'] });
      expect(meta.revision).toBe(pushed.revision);
    });

    it('can remove them all', async () => {
      const { manifest } = await edited({ kind: 'tags', tags: [] }, { tags: ['a'] });
      expect(manifest.tags).toEqual([]);
    });
  });

  describe('speakers', () => {
    const marks = [
      { atMs: 1, createdAt: 1, expandedNote: 'Alice said', expandedAt: 2, expandedStale: false },
      { atMs: 2, createdAt: 1, note: 'plain', expandedNote: null, expandedStale: false },
    ];

    it('renames one and marks written notes outdated', async () => {
      const { manifest } = await edited({ kind: 'speaker', index: 0, label: ' Élodie ' }, { marks });
      expect(manifest.speakers).toEqual([{ index: 0, label: 'Élodie' }, { index: 1, label: 'Bob' }]);
      expect(manifest.marks.map((m: { expandedStale: boolean }) => m.expandedStale)).toEqual([true, false]);
      expect(sessionView(manifest).lines[0].speaker).toBe('Élodie');
    });

    it.each([
      ['blank', { kind: 'speaker', index: 0, label: '  ' }],
      ['unknown', { kind: 'speaker', index: 9, label: 'Nobody' }],
      ['the same', { kind: 'speaker', index: 0, label: 'Alice' }],
    ] as [string, SessionEdit][])('ignores a %s one', async (_, edit) => {
      const { meta, pushed, manifest } = await edited(edit, { marks });
      expect(meta.revision).toBe(pushed.revision);
      expect(manifest.marks[0].expandedStale).toBe(false);
    });
  });

  describe('line text', () => {
    const lines = [
      {
        startMs: 1000,
        endMs: 2200,
        text: 'the budget for march',
        speakerIndex: 0,
        tokens: [
          { ms: 1000, text: ' the' },
          { ms: 1200, text: ' bud' },
          { ms: 1300, text: 'get' },
          { ms: 1500, text: ' for' },
          { ms: 1800, text: ' march' },
        ],
        textEdited: false,
        speakerEdited: false,
      },
    ];

    it('cleans the text, re-times the tokens and marks line and session edited', async () => {
      const { manifest } = await edited({ kind: 'line', at: 0, text: '  The budget   for April ' }, { lines });
      expect(manifest.lines[0]).toMatchObject({
        text: 'The budget for April',
        textEdited: true,
        speakerEdited: false,
        speakerIndex: 0,
        tokens: [
          { ms: 1000, text: ' The' },
          { ms: 1200, text: ' budget' },
          { ms: 1500, text: ' for' },
          { ms: 1850, text: ' April' },
        ],
      });
      expect(manifest.transcriptEdited).toBe(true);
    });

    it.each([
      ['blank', '  '],
      ['the same once cleaned', ' the budget  for march '],
    ])('ignores text that is %s', async (_, text) => {
      const { meta, pushed, manifest } = await edited({ kind: 'line', at: 0, text }, { lines });
      expect(meta.revision).toBe(pushed.revision);
      expect(manifest.transcriptEdited).toBeUndefined();
    });

    it('names a line by its place in the manifest, not the order shown', async () => {
      const shuffled = [
        { startMs: 9000, endMs: 9500, text: 'later', tokens: [] },
        { startMs: 0, endMs: 500, text: 'first', tokens: [] },
      ];
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, { lines: shuffled }));
      const shown = sessionView(await stored()).lines;
      expect(shown.map((l) => l.text)).toEqual(['first', 'later']);
      await editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'line', at: shown[0].at, text: 'first edited' });
      expect((await stored()).lines.map((l: { text: string }) => l.text)).toEqual(['later', 'first edited']);
    });

    it('refuses a line that isn’t there', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID));
      await expect(editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'line', at: 99, text: 'x' })).rejects.toMatchObject({ status: 400 });
    });
  });

  describe('action items', () => {
    const actionItems = [item(), item({ text: 'Hidden', deleted: true }), item({ text: 'Other' })];

    it('ticks one without calling it edited', async () => {
      const { manifest } = await edited({ kind: 'actionDone', at: 2, done: true }, { actionItems });
      expect(manifest.actionItems[2]).toMatchObject({ done: true, edited: false });
      expect(manifest.actionItems[0].done).toBe(false);
    });

    it('edits one, keeping what was extracted', async () => {
      const edit: SessionEdit = { kind: 'action', at: 0, text: ' Send it Friday ', owner: '  ', dueDate: 'by 2026-13-40' };
      const { manifest } = await edited(edit, { actionItems });
      expect(manifest.actionItems[0]).toMatchObject({
        text: 'Send it Friday',
        extractedText: 'Send it',
        owner: null,
        dueDate: null,
        edited: true,
      });
    });

    it('takes a due date that is a real date', async () => {
      const edit: SessionEdit = { kind: 'action', at: 0, text: 'Send it', owner: ' Bo ', dueDate: 'on 2028-02-29' };
      const { manifest } = await edited(edit, { actionItems });
      expect(manifest.actionItems[0]).toMatchObject({ owner: 'Bo', dueDate: '2028-02-29' });
    });

    it('ignores blank text', async () => {
      const { meta, pushed } = await edited({ kind: 'action', at: 0, text: ' ', owner: null, dueDate: null }, { actionItems });
      expect(meta.revision).toBe(pushed.revision);
    });

    it('hides a deleted one but keeps it, so the next summary doesn’t bring it back', async () => {
      const { manifest } = await edited({ kind: 'actionDelete', at: 0 }, { actionItems });
      expect(manifest.actionItems).toHaveLength(3);
      expect(manifest.actionItems[0].deleted).toBe(true);
      expect(sessionView(manifest).actionItems.map((a) => a.text)).toEqual(['Other']);
    });

    it('still finds the right item past a deleted one', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, { actionItems }));
      const other = sessionView(await stored()).actionItems.find((a) => a.text === 'Other')!;
      await editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'actionDone', at: other.at, done: true });
      expect((await stored()).actionItems[2].done).toBe(true);
    });
  });

  describe('talking points (#104)', () => {
    const point = (id: string, over: Record<string, unknown> = {}) => ({
      id,
      text: `Point ${id}`,
      covered: false,
      coveredAtMs: null,
      byUser: false,
      createdAt: 1,
      ...over,
    });
    const talkingPoints = [point('p1', { covered: true, coveredAtMs: 61_000 }), point('p2')];

    it('unticks one by hand, forgetting when it was covered', async () => {
      const { manifest } = await edited({ kind: 'pointCovered', id: 'p1', covered: false }, { talkingPoints });
      expect(manifest.talkingPoints[0]).toEqual(point('p1', { covered: false, coveredAtMs: null, byUser: true }));
    });

    it('ticks one by hand, and a tick it already had becomes the user’s', async () => {
      const { manifest } = await edited({ kind: 'pointCovered', id: 'p2', covered: true }, { talkingPoints });
      expect(manifest.talkingPoints[1]).toEqual(point('p2', { covered: true, coveredAtMs: null, byUser: true }));
      const again = await editSession(OWNER, SYNC_ID, 2, { kind: 'pointCovered', id: 'p1', covered: true });
      expect(again.revision).toBe(3);
      expect((await stored()).talkingPoints[0]).toEqual(point('p1', { covered: true, coveredAtMs: 61_000, byUser: true }));
      expect((await editSession(OWNER, SYNC_ID, 3, { kind: 'pointCovered', id: 'p1', covered: true })).revision).toBe(3);
    });

    it('edits the text as the app cleans it, ignoring a blank one', async () => {
      const { manifest } = await edited({ kind: 'point', id: 'p2', text: '  Budget\n for Q3 ' }, { talkingPoints });
      expect(manifest.talkingPoints[1]).toEqual(point('p2', { text: 'Budget for Q3' }));
      expect((await editSession(OWNER, SYNC_ID, 2, { kind: 'point', id: 'p2', text: '  ' })).revision).toBe(2);
    });

    it('adds one at the end, even to a session without points', async () => {
      const { manifest } = await edited({ kind: 'pointAdd', text: 'Hiring' });
      expect(manifest.talkingPoints).toEqual([
        { id: expect.stringMatching(/^[0-9a-f-]{36}$/), text: 'Hiring', covered: false, coveredAtMs: null, byUser: false, createdAt: expect.any(Number) },
      ]);
      expect(sessionView(manifest).talkingPoints).toMatchObject([{ text: 'Hiring', covered: false }]);
      expect((await editSession(OWNER, SYNC_ID, 2, { kind: 'pointAdd', text: ' ' })).revision).toBe(2);
    });

    it('removes one, and the field with the last', async () => {
      const { manifest } = await edited({ kind: 'pointDelete', id: 'p1' }, { talkingPoints });
      expect(manifest.talkingPoints.map((p: { id: string }) => p.id)).toEqual(['p2']);
      await editSession(OWNER, SYNC_ID, 2, { kind: 'pointDelete', id: 'p2' });
      expect('talkingPoints' in (await stored())).toBe(false);
    });

    it('refuses a point that isn’t there', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, { talkingPoints }));
      await expect(editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'point', id: 'nope', text: 'x' })).rejects.toMatchObject({ status: 400 });
      await expect(editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'pointCovered', id: 'nope', covered: true })).rejects.toMatchObject({ status: 400 });
    });

    it('keeps fields of a point this server doesn’t know', async () => {
      const { manifest } = await edited({ kind: 'point', id: 'p1', text: 'New' }, { talkingPoints: [point('p1', { colour: 'teal' })] });
      expect(manifest.talkingPoints[0]).toEqual(point('p1', { text: 'New', colour: 'teal' }));
    });
  });

  describe('Q&A (#118)', () => {
    it('clears the questions and answers, keeping the summary', async () => {
      const messages = [
        { kind: 'SUMMARY', role: 'ASSISTANT', content: 'sum', createdAt: 1 },
        { kind: 'QA', role: 'USER', content: 'q?', createdAt: 2 },
        { kind: 'QA', role: 'ASSISTANT', content: 'a.', createdAt: 3, coveredMs: 1, totalMs: 2 },
      ];
      const { pushed, meta, manifest } = await edited({ kind: 'qaClear' }, { messages });
      expect(meta.revision).toBe(pushed.revision + 1);
      expect(manifest.messages).toEqual([messages[0]]);
      expect((await editSession(OWNER, SYNC_ID, meta.revision, { kind: 'qaClear' })).revision).toBe(meta.revision);
    });
  });

  describe('what it leaves alone', () => {
    it('keeps fields this server doesn’t know, at every level', async () => {
      const over = {
        futureField: { nested: [1, 2, { deep: true }] },
        lines: [{ startMs: 0, endMs: 500, text: 'hello', tokens: [], lineFuture: 'kept' }],
        speakers: [{ index: 0, label: 'Ann', speakerFuture: 1 }],
        actionItems: [item({ itemFuture: 'kept' })],
      };
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, over));
      let revision = pushed.revision;
      const edits: SessionEdit[] = [
        { kind: 'title', title: 'New' },
        { kind: 'tags', tags: ['t'] },
        { kind: 'speaker', index: 0, label: 'Bea' },
        { kind: 'line', at: 0, text: 'hello there' },
        { kind: 'action', at: 0, text: 'Changed', owner: null, dueDate: null },
      ];
      for (const edit of edits) revision = (await editSession(OWNER, SYNC_ID, revision, edit)).revision;
      const manifest = await stored();
      expect(manifest.futureField).toEqual(over.futureField);
      expect(manifest.lines[0].lineFuture).toBe('kept');
      expect(manifest.speakers[0].speakerFuture).toBe(1);
      expect(manifest.actionItems[0].itemFuture).toBe('kept');
    });

    it('keeps the recordings’ references, and the length', async () => {
      const audio = await storeBlob(OWNER, 'recording');
      const { manifest, meta, pushed } = await edited({ kind: 'title', title: 'New' }, { audio });
      expect(meta.durationMs).toBe(pushed.durationMs);
      expect(manifest.audio).toEqual(audio);
      expect(await db().select().from(documentBlobs).where(eq(documentBlobs.syncId, SYNC_ID))).toMatchObject([{ sha256: audio.sha256 }]);
    });

    it('counts as later than what it replaced, whatever the clock says', async () => {
      const future = Date.now() + 1_000_000_000;
      const first = await pushManifest(OWNER, sessionManifest(SYNC_ID));
      await db().update(documents).set({ changedAt: future });
      const meta = await editSession(OWNER, SYNC_ID, first.revision, { kind: 'title', title: 'New' });
      expect(meta.changedAt).toBe(future + 1);
    });
  });

  describe('refusals', () => {
    it('refuses a stale revision and overwrites nothing', async () => {
      const first = await pushManifest(OWNER, sessionManifest(SYNC_ID));
      const second = await pushManifest(OWNER, sessionManifest(SYNC_ID, { title: 'Device' }), first.revision);
      await expect(editSession(OWNER, SYNC_ID, first.revision, { kind: 'title', title: 'Web' })).rejects.toMatchObject({
        status: 409,
        code: 'conflict',
      });
      expect((await stored()).title).toBe('Device');
      expect((await getDocumentMeta(OWNER, 'session', SYNC_ID))?.revision).toBe(second.revision);
    });

    it('refuses a manifest from a newer app', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID, { schema: 2 }));
      await expect(editSession(OWNER, SYNC_ID, pushed.revision, { kind: 'title', title: 'Web' })).rejects.toMatchObject({
        status: 422,
        code: 'newer_schema',
      });
      expect((await stored()).title).toBe(`Session ${SYNC_ID}`);
    });

    it('refuses a deleted session', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID));
      const gone = await deleteDocument(OWNER, 'session', SYNC_ID, pushed.revision);
      await expect(editSession(OWNER, SYNC_ID, gone.revision, { kind: 'title', title: 'Web' })).rejects.toMatchObject({ status: 404 });
    });

    it('treats another owner’s session as absent', async () => {
      const pushed = await pushManifest(OWNER, sessionManifest(SYNC_ID));
      await expect(editSession('mallory', SYNC_ID, pushed.revision, { kind: 'title', title: 'Web' })).rejects.toMatchObject({ status: 404 });
      expect((await stored()).title).toBe(`Session ${SYNC_ID}`);
    });
  });

  describe('what follows an edit', () => {
    it('updates the library index and search', async () => {
      const { pushed } = await edited({ kind: 'title', title: 'Quarterly planning' });
      const line = await editSession(OWNER, SYNC_ID, pushed.revision + 1, { kind: 'line', at: 0, text: 'Zebras everywhere' });
      expect(line.revision).toBe(pushed.revision + 2);
      const [row] = await db()
        .select()
        .from(sessionIndex)
        .where(and(eq(sessionIndex.owner, OWNER), eq(sessionIndex.syncId, SYNC_ID)));
      expect(row.title).toBe('Quarterly planning');
      expect((await searchSessions(OWNER, 'zebras')).sessions).toHaveLength(1);
      expect((await searchSessions('mallory', 'zebras')).sessions).toHaveLength(0);
    });
  });

  describe('parseEdit', () => {
    it.each([
      [null],
      [{}],
      [{ kind: 'nope' }],
      [{ kind: 'title' }],
      [{ kind: 'tags', tags: ['a', 1] }],
      [{ kind: 'speaker', index: -1, label: 'x' }],
      [{ kind: 'line', at: 1.5, text: 'x' }],
      [{ kind: 'actionDone', at: 0, done: 'yes' }],
      [{ kind: 'action', at: 0, text: 'x', owner: 1, dueDate: null }],
      [{ kind: 'actionDelete' }],
      [{ kind: 'pointCovered', id: 'p1' }],
      [{ kind: 'point', id: 1, text: 'x' }],
      [{ kind: 'pointAdd' }],
      [{ kind: 'pointDelete', at: 0 }],
    ])('refuses %j', (value) => {
      expect(() => parseEdit(value)).toThrow();
    });

    it('takes a good one', () => {
      expect(parseEdit({ kind: 'actionDelete', at: 0 })).toEqual({ kind: 'actionDelete', at: 0 });
    });
  });
});
