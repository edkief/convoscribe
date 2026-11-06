import { describe, expect, it } from 'vitest';
import {
  ACTION_ITEMS_CONTRACT,
  BUILT_IN_TEMPLATES,
  chunkTranscript,
  fit,
  lineText,
  MARKS_HEADER,
  MEETING,
  MERGE_NOTES_SYSTEM,
  mergeNotesMessages,
  notesText,
  PART_NOTES_SYSTEM,
  partsIntro,
  summaryFromPartsMessages,
  summaryMessages,
  summaryPartMessages,
  summarySystem,
  SUMMARY_GUARDRAILS,
  SUMMARY_PREAMBLE,
  transcriptText,
  type PromptLine,
} from './summary-prompt';

// The cases of the app's PromptBuilderTest (summary), TranscriptTruncatorTest
// and TranscriptChunkerTest, so both write the same prompts.

const SUMMARY_SYSTEM =
  'You summarise conversation transcripts. Write in the same language as the transcript. ' +
  'Use this structure:\n' +
  '1. Overview: one short paragraph.\n' +
  '2. Key points: bullet list.\n' +
  '3. Decisions: bullet list, or "None".\n' +
  '4. Action items: bullet list with the owner\'s name when known, or "None".\n' +
  'Use the speaker names exactly as they appear. ' +
  'Do not invent facts that are not in the transcript.\n' +
  'After the summary, list the action items it contains as a JSON array in a ```json code block, ' +
  'one object per item: {"text": what is to be done, "owner": the name of who will do it, or null, ' +
  '"due": the due date as YYYY-MM-DD if one was said, or null, ' +
  '"at": the transcript timestamp like "12:34" where it was agreed, or null}. ' +
  'Write [] if there are none. Write nothing after the code block.';

const padded = (n: number, fill: number) => Array.from({ length: n }, (_, i) => `L${String(i + 1).padStart(3, '0')}:${'x'.repeat(fill)}\n`).join('');

describe('summary prompts', () => {
  it('match the app word for word', () => {
    expect(summarySystem(MEETING.body)).toBe(SUMMARY_SYSTEM);
    expect(summaryMessages('Alice: hi\n', 1000)[0].content).toBe(SUMMARY_SYSTEM);
  });

  it('state the recording date first', () => {
    const user = summaryMessages('Alice: hi\n', 1000, undefined, [{ atMs: 2000, note: 'deck' }], { date: '2026-10-30', weekday: 'Friday' })[1].content;
    expect(user.startsWith('The conversation was recorded on 2026-10-30 (Friday).\n\n' + MARKS_HEADER)).toBe(true);
    expect(summaryMessages('Alice: hi\n', 1000)[1].content.startsWith('Transcript:')).toBe(true);
  });

  it('frame every built-in with the preamble and guardrails', () => {
    expect(BUILT_IN_TEMPLATES.map((t) => t.key)).toEqual(['meeting', 'interview', 'lecture', 'one_on_one', 'visit', 'brief']);
    for (const template of BUILT_IN_TEMPLATES) {
      const system = summaryMessages('Alice: hi\n', 1000, template.body)[0].content;
      expect(system.startsWith(SUMMARY_PREAMBLE)).toBe(true);
      expect(system.endsWith(SUMMARY_GUARDRAILS + '\n' + ACTION_ITEMS_CONTRACT)).toBe(true);
      expect(system).toContain(template.body.trim());
    }
  });

  it('trim a user body and always add the guardrails', () => {
    expect(summarySystem('  \n List the jokes. \n\n')).toBe(SUMMARY_PREAMBLE + 'List the jokes.\n' + SUMMARY_GUARDRAILS + '\n' + ACTION_ITEMS_CONTRACT);
    expect(summarySystem('   ')).toBe(SUMMARY_PREAMBLE + SUMMARY_GUARDRAILS + '\n' + ACTION_ITEMS_CONTRACT);
  });

  it('send the system prompt, then the transcript', () => {
    const transcript = 'Alice: hello\nBob: hi\n';
    const messages = summaryMessages(transcript, 1000);
    expect(messages.map((m) => m.role)).toEqual(['system', 'user']);
    expect(messages[1].content).toBe('Transcript:\n\n' + transcript);
  });

  it('truncate an oversized transcript but keep the marks', () => {
    const user = summaryMessages(padded(100, 94), 1000, undefined, [{ atMs: 1000, note: 'keep me' }])[1].content;
    expect(user.startsWith(MARKS_HEADER + '[00:01] keep me\n\nTranscript:')).toBe(true);
    expect(user).toContain('characters omitted');
  });

  it('list the marks in time order before the transcript', () => {
    const marks = [
      { atMs: 754_000, note: null },
      { atMs: 65_000, note: 'the decision' },
    ];
    expect(summaryMessages('Alice: hello\n', 1000, undefined, marks)[1].content).toBe(
      MARKS_HEADER + '[01:05] the decision\n[12:34] (no note)\n\nTranscript:\n\nAlice: hello\n',
    );
  });

  it('head a part with its number and span and its marks', () => {
    const chunk = { text: '[01:00] Alice: hi', startMs: 60_000, endMs: 125_000, firstLine: 3, lastLine: 3 };
    const messages = summaryPartMessages(chunk, 2, 5, [{ atMs: 61_000, note: 'here' }]);
    expect(messages[0].content).toBe(PART_NOTES_SYSTEM);
    expect(messages[1].content).toBe(MARKS_HEADER + '[01:01] here\n\nTranscript, part 2 of 5 [01:00–02:05]:\n\n[01:00] Alice: hi');
  });

  it('write the summary from every part notes in order', () => {
    const notes = [
      { startMs: 0, endMs: 60_000, text: ' first notes ' },
      { startMs: 60_000, endMs: 120_000, text: 'second notes' },
    ];
    const messages = summaryFromPartsMessages(notes, 10_000, 'Three bullets.');
    expect(messages[0].content).toBe(summarySystem('Three bullets.'));
    expect(messages[1].content).toBe(partsIntro(2) + 'Part 1 [00:00–01:00]:\nfirst notes\n\nPart 2 [01:00–02:00]:\nsecond notes');
    expect(mergeNotesMessages(notes)).toEqual([
      { role: 'system', content: MERGE_NOTES_SYSTEM },
      { role: 'user', content: 'Notes, in time order:\n\n' + notesText(notes) },
    ]);
  });

  it('render lines as the app does', () => {
    expect(lineText({ startMs: 3_723_000, speaker: 'Alice', text: 'hi' })).toBe('[1:02:03] Alice: hi');
    expect(lineText({ startMs: 5_000, speaker: null, text: 'hi' })).toBe('[00:05] hi');
  });
});

describe('fit', () => {
  it('returns a transcript that fits as it is', () => {
    expect(fit('Hello, world.\n', 100)).toBe('Hello, world.\n');
    expect(fit('abc', 3)).toBe('abc');
  });

  it('keeps whole head and tail lines within budget', () => {
    const transcript = padded(100, 94);
    expect(transcript).toHaveLength(10_000);
    const fitted = fit(transcript, 2000);
    expect(fitted.length).toBeLessThanOrEqual(2000);
    expect(fitted.startsWith(transcript.slice(0, 99))).toBe(true);
    expect(fitted.endsWith(transcript.slice(9900, 9999))).toBe(true);
    const headEnd = fitted.indexOf('\n[… ');
    const tailStart = fitted.lastIndexOf('…]\n') + 3;
    expect(fitted[headEnd - 1]).not.toBe('\n');
    expect(fitted[tailStart]).not.toBe('\n');
  });

  it('counts exactly what it drops', () => {
    const transcript = padded(100, 80);
    const fitted = fit(transcript, 1000);
    const start = fitted.indexOf('[… ');
    const end = fitted.indexOf(' characters omitted …]', start);
    const dropped = Number(fitted.slice(start + 3, end));
    expect(dropped).toBe(transcript.length - (start - 1) - (fitted.length - end - 23));
  });

  it('keeps a slice of a single long line', () => {
    const fitted = fit('a'.repeat(500), 200);
    expect(fitted.length).toBeLessThanOrEqual(200);
    expect(fitted).toContain('characters omitted');
    expect(fitted.startsWith('a') && fitted.endsWith('a')).toBe(true);
  });

  it('never goes over budget', () => {
    const transcript = Array.from({ length: 50 }, (_, i) => `line-${String(i + 1).padStart(2, '0')}: ${'x'.repeat(80)}\n`).join('');
    for (let budget = 100; budget <= 2000; budget += 100) expect(fit(transcript, budget).length).toBeLessThanOrEqual(budget);
  });
});

describe('chunkTranscript', () => {
  const lines = (count: number): PromptLine[] =>
    Array.from({ length: count }, (_, i) => ({
      startMs: i * 10_000,
      endMs: i * 10_000 + 9000,
      speaker: i % 2 === 0 ? 'Alice' : 'Bob',
      text: `line number ${i} says something worth keeping here.`,
    }));

  it('gives no chunks for no lines', () => {
    expect(chunkTranscript([], 1000)).toEqual([]);
  });

  it('gives one chunk equal to the text under budget', () => {
    const input = lines(5);
    expect(chunkTranscript(input, 10_000)).toEqual([{ text: transcriptText(input), startMs: 0, endMs: 49_000, firstLine: 0, lastLine: 4 }]);
  });

  it('keeps each chunk within budget and of whole lines, covering every line in order', () => {
    const input = lines(200);
    const rendered = new Set(input.map(lineText));
    const chunks = chunkTranscript(input, 1000);
    expect(chunks.length).toBeGreaterThan(10);
    for (const chunk of chunks) {
      expect(chunk.text.length).toBeLessThanOrEqual(1000);
      for (const line of chunk.text.split('\n')) expect(rendered.has(line)).toBe(true);
    }
    expect(chunks[0].firstLine).toBe(0);
    expect(chunks.at(-1)!.lastLine).toBe(199);
    for (let i = 1; i < chunks.length; i++) {
      expect(chunks[i].firstLine).toBeLessThanOrEqual(chunks[i - 1].lastLine + 1);
      expect(chunks[i].lastLine).toBeGreaterThan(chunks[i - 1].lastLine);
    }
  });

  it('opens each chunk with the last lines of the previous one', () => {
    const chunks = chunkTranscript(lines(200), 1000, 150);
    for (let i = 1; i < chunks.length; i++) {
      const [a, b] = [chunks[i - 1], chunks[i]];
      expect(b.firstLine).toBeLessThanOrEqual(a.lastLine);
      const shared = a.text.split('\n').slice(-(a.lastLine - b.firstLine + 1)).join('\n');
      expect(b.text.startsWith(shared)).toBe(true);
      expect(shared.length).toBeLessThanOrEqual(150);
    }
  });

  it('tiles the timeline without overlap', () => {
    const chunks = chunkTranscript(lines(100), 800, 0);
    for (let i = 1; i < chunks.length; i++) {
      expect(chunks[i].firstLine).toBe(chunks[i - 1].lastLine + 1);
      expect(chunks[i - 1].endMs).toBe(chunks[i].startMs);
    }
    expect(chunks.at(-1)!.endMs).toBe(999_000);
  });

  it('splits a line longer than the budget at spaces', () => {
    const long = { startMs: 0, endMs: 5000, speaker: null, text: 'word '.repeat(100).trim() };
    const chunks = chunkTranscript([long], 120, 0);
    expect(chunks.length).toBeGreaterThanOrEqual(4);
    for (const chunk of chunks) {
      expect(chunk.text.length).toBeLessThanOrEqual(120);
      expect(chunk.text.split(' ').every((w) => w === 'word' || w === '[00:00]')).toBe(true);
    }
    expect(chunks.reduce((n, c) => n + c.text.split(' ').filter((w) => w === 'word').length, 0)).toBe(100);
  });
});
