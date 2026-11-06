import { describe, expect, it } from 'vitest';
import { applyAutoTitle, applyMarkNotes, applySuggestedTitle, applyTicks, marksToWrite, openPoints, titleSource } from './assist';

describe('titles (#119)', () => {
  it('names only a date-based title automatically, as the app does', () => {
    const fresh = { title: 'Recording 2025-10-09', titleSource: 'default' };
    expect(applyAutoTitle(fresh, 'Budget')).toBe(true);
    expect(fresh).toEqual({ title: 'Budget', titleSource: 'auto', previousTitle: 'Recording 2025-10-09' });
    for (const source of ['user', 'auto', undefined]) {
      const m = { title: 'Mine', titleSource: source };
      expect(applyAutoTitle(m, 'Budget')).toBe(false);
      expect(m.title).toBe('Mine');
    }
  });

  it('replaces any title when asked, keeping the one from before the model’s', () => {
    const m: Record<string, unknown> = { title: 'Mine', titleSource: 'user', previousTitle: null };
    expect(applySuggestedTitle(m, 'Budget')).toBe(true);
    expect(m).toEqual({ title: 'Budget', titleSource: 'auto', previousTitle: 'Mine' });
    expect(applySuggestedTitle(m, 'Q3 budget')).toBe(true);
    expect(m.previousTitle).toBe('Mine');
    expect(applySuggestedTitle(m, 'Q3 budget')).toBe(false);
  });

  it('writes from the latest summary, else the transcript, else nothing', () => {
    const lines = [{ startMs: 0, endMs: 1, text: 'Hi.', speakerIndex: null }];
    const summaries = [
      { kind: 'SUMMARY', role: 'ASSISTANT', content: 'old', createdAt: 1 },
      { kind: 'SUMMARY', role: 'ASSISTANT', content: 'new', createdAt: 2 },
    ];
    expect(titleSource({ lines, messages: summaries, language: 'fr' })).toEqual({ from: { summary: 'new' }, language: 'fr' });
    expect(titleSource({ lines })).toEqual({ from: { transcript: '[00:00] Hi.' }, language: null });
    expect(titleSource({ lines: [{ startMs: 0, endMs: 1, text: '  ' }] })).toBeNull();
  });
});

describe('talking points (#119)', () => {
  const points = () => [
    { id: 'a', text: 'A' },
    { id: 'b', text: 'B', covered: true, coveredAtMs: 5 },
    { id: 'c', text: 'C', byUser: true },
    { id: 'd', text: 'D', covered: false, byUser: false },
  ];

  it('offers only points neither covered nor set by hand', () => {
    expect(openPoints({ talkingPoints: points() })).toEqual([
      { id: 'a', text: 'A' },
      { id: 'd', text: 'D' },
    ]);
    expect(openPoints({})).toEqual([]);
  });

  it('ticks only open points', () => {
    const m = { talkingPoints: points() };
    expect(applyTicks(m, new Map([['a', 10], ['b', 20], ['c', 30], ['zz', 1]]))).toBe(1);
    expect(m.talkingPoints[0]).toEqual({ id: 'a', text: 'A', covered: true, coveredAtMs: 10, byUser: false });
    expect(m.talkingPoints.slice(1)).toEqual(points().slice(1));
  });
});

describe('mark notes (#119)', () => {
  it('writes marks without a note or with a stale one', () => {
    const marks = [
      { atMs: 1, note: 'a' },
      { atMs: 2, expandedNote: 'x', expandedStale: true },
      { atMs: 3, expandedNote: 'y', expandedStale: false },
      { note: 'no time' },
    ];
    expect(marksToWrite({ marks })).toEqual([
      { at: 0, atMs: 1, note: 'a' },
      { at: 1, atMs: 2, note: null },
    ]);
    const m = { marks };
    expect(applyMarkNotes(m, new Map([[1, 'Full.'], [9, 'gone']]), 42)).toBe(1);
    expect(m.marks[1]).toEqual({ atMs: 2, expandedNote: 'Full.', expandedStale: false, expandedAt: 42 });
  });
});
