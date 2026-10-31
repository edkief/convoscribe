import { describe, expect, it } from 'vitest';
import { sessionManifest } from '@/test/sessions';
import { attachment, markLineIndex, sessionView, timestamp, toMarkdown, toSrt, toVtt } from './transcript-export';

const view = (over: Record<string, unknown> = {}) => sessionView(sessionManifest('s1', { title: 'Weekly sync', ...over }));

describe('sessionView', () => {
  it('reads speakers, summaries, questions, marks and live action items', () => {
    const v = view({
      messages: [
        { kind: 'SUMMARY', role: 'ASSISTANT', content: '## Summary', createdAt: 1 },
        { kind: 'QA', role: 'USER', content: 'Who?', createdAt: 2 },
        { kind: 'QA', role: 'ASSISTANT', content: 'Alice.', createdAt: 3 },
      ],
      marks: [{ atMs: 5000, note: 'Key point', createdAt: 1 }],
      actionItems: [
        { text: 'Send notes', extractedText: 'x', owner: 'Alice', dueDate: '2026-11-02', atMs: 4500, done: true, createdAt: 1 },
        { text: 'Gone', extractedText: 'x', deleted: true, createdAt: 1 },
      ],
    });
    expect(v.lines.map((l) => l.speaker)).toEqual(['Alice', 'Bob']);
    expect(v.summaries.map((m) => m.content)).toEqual(['## Summary']);
    expect(v.questions.map((m) => m.role)).toEqual(['USER', 'ASSISTANT']);
    expect(v.marks).toEqual([{ atMs: 5000, note: 'Key point', expandedNote: null, expandedStale: false }]);
    expect(v.actionItems).toEqual([{ text: 'Send notes', owner: 'Alice', dueDate: '2026-11-02', atMs: 4500, done: true }]);
  });

  it('leaves a line unlabelled when its speaker is unknown, and survives garbage', () => {
    expect(view({ speakers: [] }).lines.map((l) => [l.speaker, l.speakerIndex])).toEqual([
      [null, null],
      [null, null],
    ]);
    const empty = sessionView('nonsense');
    expect(empty.title).toBe('Untitled session');
    expect(empty.lines).toEqual([]);
  });
});

describe('timestamps and marks, as the app writes them', () => {
  it.each([
    [0, '00:00'],
    [65_432, '01:05'],
    [3_600_000, '1:00:00'],
    [36_061_000, '10:01:01'],
    [-5, '00:00'],
  ])('%i ms is %s', (ms, text) => {
    expect(timestamp(ms)).toBe(text);
  });

  it('puts a mark on the line being spoken, or the first line', () => {
    const lines = [{ startMs: 1000 }, { startMs: 5000 }];
    expect(markLineIndex(lines, 0)).toBe(0);
    expect(markLineIndex(lines, 4999)).toBe(0);
    expect(markLineIndex(lines, 5000)).toBe(1);
    expect(markLineIndex([], 5)).toBeNull();
  });
});

describe('exports', () => {
  it('writes Markdown with details, speakers and marks above their line', () => {
    const md = toMarkdown(view({ tags: ['Work'], marks: [{ atMs: 4500, note: 'Note', expandedNote: 'Line one\n\nLine two', createdAt: 1 }] }));
    expect(md).toBe(
      [
        '# Weekly sync',
        '',
        '- Recorded: 2025-10-09 08:53 UTC',
        '- Length: 01:05',
        '- Speakers: Alice, Bob',
        '- Tags: Work',
        '',
        '## Transcript',
        '',
        '**[00:00] Alice:** Hello there.',
        '',
        '> ⚑ [00:04] Mark: Note',
        '> Line one',
        '> Line two',
        '',
        '**[00:04] Bob:** Hi, how are you?',
        '',
      ].join('\n'),
    );
  });

  it('writes SRT cues, ending an untimed line at the next one', () => {
    const lines = [
      { startMs: 0, endMs: 0, text: 'First', speakerIndex: 0 },
      { startMs: 3_723_456, endMs: 3_725_000, text: 'Second\nline', speakerIndex: 1 },
      { startMs: 3_726_000, endMs: 3_726_000, text: '   ', speakerIndex: 1 },
    ];
    expect(toSrt(view({ lines }))).toBe(
      '1\n00:00:00,000 --> 01:02:03,456\nAlice: First\n\n2\n01:02:03,456 --> 01:02:05,000\nBob: Second line\n',
    );
  });

  it('writes WebVTT with voice spans and escaped text', () => {
    const lines = [{ startMs: 1500, endMs: 1500, text: 'a < b & c', speakerIndex: 0 }];
    const speakers = [{ index: 0, label: 'Ann <Ops>' }];
    expect(toVtt(view({ lines, speakers }))).toBe(
      'WEBVTT\n\n00:00:01.500 --> 00:00:02.500\n<v Ann &lt;Ops&gt;>a &lt; b &amp; c\n',
    );
  });

  it('names a download after the title, with an ASCII fallback', () => {
    expect(attachment('Café / "plan"', 'md')).toBe(
      `attachment; filename="Caf_ _ _plan_.md"; filename*=UTF-8''Caf%C3%A9%20_%20_plan_.md`,
    );
    expect(attachment('  ', 'srt')).toBe(`attachment; filename="session.srt"; filename*=UTF-8''session.srt`);
  });
});
