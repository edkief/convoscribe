import { describe, expect, it } from 'vitest';
import {
  cleanTitle,
  MARK_NOTE_SYSTEM,
  MARK_NOTE_WITHOUT_NOTE,
  markNoteMessages,
  markWindow,
  parseCoveredPoints,
  TALKING_POINTS_SYSTEM,
  talkingPointsBudget,
  talkingPointsMessages,
  TITLE_SUMMARY_MAX_CHARS,
  TITLE_SYSTEM,
  titleMessages,
} from './assist-prompt';
import { SUMMARY_GUARDRAILS } from './summary-prompt';

// Cases from the app's TitleCleanerTest, TalkingPointAnswerTest, MarkWindowTest and PromptBuilderTest.

describe('cleanTitle (the app’s TitleCleaner)', () => {
  it('strips quotes, labels, Markdown and trailing punctuation', () => {
    expect(cleanTitle('"Budget planning for Q3."')).toBe('Budget planning for Q3');
    expect(cleanTitle('Title: Budget planning')).toBe('Budget planning');
    expect(cleanTitle('**Titre : « Planification du budget »**')).toBe('Planification du budget');
    expect(cleanTitle('# Hiring a designer')).toBe('Hiring a designer');
    expect(cleanTitle('\n\n  Release date!  \nSome explanation')).toBe('Release date');
  });

  it('caps words and length', () => {
    expect(cleanTitle('one two three four five six seven eight')).toBe('one two three four five six');
    expect(cleanTitle('x'.repeat(150))!.length).toBe(100);
  });

  it('rejects blank or punctuation-only answers', () => {
    expect(cleanTitle('')).toBeNull();
    expect(cleanTitle('   \n  ')).toBeNull();
    expect(cleanTitle('""...')).toBeNull();
  });
});

describe('titleMessages', () => {
  it('asks for a short title in the session language, from the summary', () => {
    const english = titleMessages({ summary: '  Overview: the team set the Q3 budget.  ' }, 'en');
    expect(english).toEqual([
      { role: 'system', content: TITLE_SYSTEM + ' Write it in English.' },
      { role: 'user', content: 'Summary:\n\nOverview: the team set the Q3 budget.' },
    ]);
    const french = titleMessages({ summary: 'x'.repeat(10_000) }, 'fr');
    expect(french[0].content).toBe(TITLE_SYSTEM + ' Write it in French.');
    expect(french[1].content.length).toBe('Summary:\n\n'.length + TITLE_SUMMARY_MAX_CHARS);
  });

  it('uses the transcript, fitted to the same budget, without a summary', () => {
    expect(titleMessages({ transcript: '[00:00] Alice: Hello.' }, null)[1].content).toBe('Transcript:\n\n[00:00] Alice: Hello.');
    const long = Array.from({ length: 500 }, (_, i) => `[00:${i}] line number ${i}`).join('\n');
    expect(titleMessages({ transcript: long }, 'en')[1].content.length).toBeLessThanOrEqual('Transcript:\n\n'.length + TITLE_SUMMARY_MAX_CHARS);
  });
});

describe('markWindow (the app’s MarkWindow)', () => {
  const line = (startS: number, endS: number, text: string) => ({ startMs: startS * 1000, endMs: endS * 1000, speaker: null, text });
  const lines = [line(0, 10, 'a'), line(50, 70, 'b'), line(100, 110, 'c'), line(150, 170, 'd'), line(175, 180, 'e'), line(300, 310, 'f')];
  const texts = (atS: number) => markWindow(lines, atS * 1000).map((l) => l.text);

  it('takes a minute before and half a minute after, in whole lines', () => {
    expect(texts(115)).toEqual(['b', 'c']);
  });

  it('reaches further before than after', () => {
    expect(texts(160)).toEqual(['c', 'd', 'e']);
  });

  it('always keeps the line being spoken at the mark', () => {
    expect(texts(260)).toEqual(['e']);
  });

  it('gives a mark before anything was said the opening', () => {
    expect(markWindow([line(120, 130, 'late'), line(200, 210, 'later')], 5000).map((l) => l.text)).toEqual(['late']);
  });

  it('is empty without lines', () => {
    expect(markWindow([], 5000)).toEqual([]);
  });
});

describe('markNoteMessages', () => {
  it('sends the note, then the transcript around it', () => {
    const window = [
      { startMs: 60_000, endMs: 64_000, speaker: 'Dana', text: 'Twelve euros a seat.' },
      { startMs: 64_000, endMs: 66_000, speaker: 'Bob', text: 'Fine by me.' },
    ];
    const messages = markNoteMessages({ atMs: 65_000, note: 'pricing' }, window, 1000);
    expect(messages[0]).toEqual({ role: 'system', content: MARK_NOTE_SYSTEM });
    expect(messages[0].content.endsWith(SUMMARY_GUARDRAILS)).toBe(true);
    expect(messages[1].content).toBe('Marked at [01:05]. The user\'s note: "pricing"\n\nTranscript around it:\n\n[01:00] Dana: Twelve euros a seat.\n[01:04] Bob: Fine by me.');
  });

  it('asks what was discussed for a mark without a note', () => {
    const user = markNoteMessages({ atMs: 2000, note: '  ' }, [{ startMs: 0, endMs: 1000, speaker: null, text: 'Hello.' }], 1000)[1].content;
    expect(user.startsWith('Marked at [00:02]. ' + MARK_NOTE_WITHOUT_NOTE)).toBe(true);
  });

  it('fits the window to the budget', () => {
    const window = Array.from({ length: 200 }, (_, i) => ({ startMs: i * 1000, endMs: i * 1000 + 900, speaker: null, text: 'word '.repeat(10) }));
    const user = markNoteMessages({ atMs: 0, note: null }, window, 2000)[1].content;
    expect(user.split('Transcript around it:\n\n')[1].length).toBeLessThanOrEqual(2000);
  });
});

describe('talking-point prompts', () => {
  it('numbers the open points, then sends the transcript', () => {
    expect(talkingPointsMessages(['Budget', 'Hiring'], '[00:20] We need more people.')).toEqual([
      { role: 'system', content: TALKING_POINTS_SYSTEM },
      { role: 'user', content: 'Points not covered yet:\n1. Budget\n2. Hiring\n\nLatest transcript:\n\n[00:20] We need more people.' },
    ]);
  });

  it('stays within the budget', () => {
    const points = ['Budget for next year', 'Hiring plan', 'Office move'];
    const budget = talkingPointsBudget(points, 6000);
    const messages = talkingPointsMessages(points, 'x'.repeat(budget));
    expect(messages.reduce((n, m) => n + m.content.length, 0)).toBeLessThanOrEqual(6000);
    expect(budget).toBeGreaterThan(5000);
  });

  it('always leaves some transcript', () => {
    const points = Array.from({ length: 100 }, (_, i) => `A long point to raise number ${i} ` + 'y'.repeat(50));
    expect(talkingPointsBudget(points, 2000)).toBe(1000);
  });
});

describe('parseCoveredPoints (the app’s TalkingPointAnswer)', () => {
  const parse = (answer: string, count = 3) => parseCoveredPoints(answer, count);
  const p = (number: number, atMs: number | null = null) => ({ number, atMs });

  it('reads points and times', () => {
    expect(parse('[{"point": 2, "at": "12:34"}, {"point": 3, "at": "[1:02:03]"}]')).toEqual([p(2, 754_000), p(3, 3_723_000)]);
  });

  it('reads an empty array as nothing covered', () => {
    expect(parse('[]')).toEqual([]);
  });

  it('takes a code fence, prose and a reasoning block', () => {
    const answer = '<think>Point [1] is about the budget, maybe.</think>\nHere you go:\n```json\n[{"point": 1, "at": "00:40"}]\n```\nHope that helps.';
    expect(parse(answer)).toEqual([p(1, 40_000)]);
  });

  it('drops a reasoning block without its start', () => {
    expect(parse('thinking about [1]...</think>[2]')).toEqual([p(2)]);
  });

  it('accepts bare and quoted numbers, and unquoted keys', () => {
    expect(parse('[1, "3"]')).toEqual([p(1), p(3)]);
    expect(parse('[{"point": "2"}]')).toEqual([p(2)]);
    expect(parse('[{point: 2, at: "00:03"}]')).toEqual([p(2, 3000)]);
  });

  it('drops out-of-range and repeated numbers', () => {
    expect(parse('[{"point": 0}, {"point": 2, "at": "00:05"}, {"point": 4}, {"point": 2, "at": "00:09"}, 7]')).toEqual([p(2, 5000)]);
  });

  it('ignores an at that is no time', () => {
    expect(parse('[{"point": 1, "at": "early on"}]')).toEqual([p(1)]);
    expect(parse('[{"point": 1, "at": null}]')).toEqual([p(1)]);
  });

  it('skips objects without a point', () => {
    expect(parse('[{"at": "00:01"}, {"point": "three"}, {"point": 3}]')).toEqual([p(3)]);
  });

  it('reads prose without numbers as nothing covered', () => {
    expect(parse('None of the points were discussed.')).toEqual([]);
    expect(parse('')).toEqual([]);
  });

  it('gives null for an unreadable answer', () => {
    expect(parse('Points 1 and 2 were covered.')).toBeNull();
    expect(parse('[{"point": 1, ')).toBeNull();
    expect(parse('{"point": 1}')).toBeNull();
    expect(parse('<think>Still thinking about [1')).toBeNull();
  });
});
