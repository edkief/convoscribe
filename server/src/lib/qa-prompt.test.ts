import { describe, expect, it } from 'vitest';
import type { LlmMessage } from './llm-client';
import {
  coverageMinutes,
  EXCERPT_GAP,
  QA_EXCERPTS_PREFIX,
  QA_SYSTEM_PREFIX,
  questionFromExcerptsMessages,
  questionMessages,
  scores,
  selectExcerpts,
  words,
} from './qa-prompt';
import type { TranscriptChunk } from './summary-prompt';

/** Ten one-minute chunks of filler; `special` replaces chunk texts by index (the app's `ChunkRetrieverTest`). */
const chunks = (special: Record<number, string> = {}): TranscriptChunk[] =>
  Array.from({ length: 10 }, (_, i) => ({
    text: special[i] ?? `[0${i}:00] Alice: we talked about the weather and the traffic again`,
    startMs: i * 60_000,
    endMs: (i + 1) * 60_000,
    firstLine: i,
    lastLine: i,
  }));

const best = (list: number[]) => list.indexOf(Math.max(...list));
const KOWALSKI = { 5: '[05:00] Bob: the invoice for Kowalski is overdue' };

describe('excerpts (ChunkRetriever)', () => {
  it('lower-cases words and keeps accented letters', () => {
    expect(words('Le BUDGET a été: 2026!')).toEqual(['le', 'budget', 'été', '2026']);
  });

  it('ranks a rare term above filler', () => {
    const s = scores(chunks(KOWALSKI), 'What about the Kowalski invoice?');
    expect(best(s)).toBe(5);
    expect(s[0]).toBeLessThan(s[5] / 10);
  });

  it('matches ignoring accents', () => {
    expect(best(scores(chunks({ 3: '[03:00] Bob: la Réunion budgétaire est jeudi' }), 'reunion budgetaire'))).toBe(3);
  });

  it('takes the matching chunk and its neighbours, in time order', () => {
    const list = chunks(KOWALSKI);
    const result = selectExcerpts(list, 'Kowalski invoice', list[0].text.length * 3 + 20);
    expect(result.chunks.map((c) => c.firstLine)).toEqual([4, 5, 6]);
    expect(result.coveredMs).toBe(180_000);
    expect(result.totalMs).toBe(600_000);
  });

  it('prefers the neighbours of a strong match over common-word matches', () => {
    const list = chunks(KOWALSKI);
    const result = selectExcerpts(list, 'What did they say about the Kowalski invoice?', list[0].text.length * 3 + 20);
    expect(result.chunks.map((c) => c.firstLine)).toEqual([4, 5, 6]);
  });

  it('stays within the budget', () => {
    const result = selectExcerpts(chunks(), 'weather', 200);
    expect(result.chunks.length).toBeGreaterThan(0);
    expect(result.chunks.reduce((sum, c) => sum + c.text.length + 5, 0)).toBeLessThanOrEqual(200);
  });

  it('spreads over the conversation when no word matches', () => {
    const list = chunks();
    const picked = selectExcerpts(list, 'zzz', (list[0].text.length + 5) * 3).chunks.map((c) => c.firstLine);
    expect(picked).toHaveLength(3);
    expect(picked[0]).toBeLessThanOrEqual(2);
    expect(picked[2]).toBeGreaterThanOrEqual(7);
  });

  it('gives nothing for no chunks', () => {
    expect(selectExcerpts([], 'anything', 1000)).toEqual({ chunks: [], coveredMs: 0, totalMs: 0 });
  });

  it('counts overlapping chunks once in the coverage', () => {
    const list = chunks().map((c, i) => ({ ...c, endMs: c.endMs + (i < 9 ? 30_000 : 0) }));
    expect(selectExcerpts(list, 'weather', Number.MAX_SAFE_INTEGER).coveredMs).toBe(600_000);
  });
});

describe('question messages (PromptBuilder)', () => {
  const turn = (i: number): LlmMessage => ({ role: i % 2 === 1 ? 'user' : 'assistant', content: `m${i}` });

  it('keeps the last six turns and trims the question', () => {
    const history = Array.from({ length: 10 }, (_, i) => turn(i + 1));
    const messages = questionMessages('[00:00] Alice: hi', 1000, history, '   what did Alice say?   ');
    expect(messages).toHaveLength(8);
    expect(messages[0]).toEqual({ role: 'system', content: QA_SYSTEM_PREFIX + '[00:00] Alice: hi' });
    expect(messages.slice(1, 7)).toEqual(history.slice(-6));
    expect(messages[7]).toEqual({ role: 'user', content: 'what did Alice say?' });
  });

  it('leaves out other roles before keeping the window', () => {
    const history: LlmMessage[] = [
      { role: 'user', content: 'u1' },
      { role: 'assistant', content: 'a1' },
      { role: 'system', content: 'x' },
      { role: 'user', content: 'u2' },
      { role: 'assistant', content: 'a2' },
      { role: 'user', content: 'u3' },
      { role: 'assistant', content: 'a3' },
    ];
    expect(questionMessages('t', 1000, history, 'ping').slice(1, 7).map((m) => m.content)).toEqual(['u1', 'a1', 'u2', 'a2', 'u3', 'a3']);
  });

  it('sends a short or empty history as it is', () => {
    expect(questionMessages('t', 1000, [turn(1), turn(2)], '?')).toHaveLength(4);
    expect(questionMessages('t', 1000, [], 'first question')).toEqual([
      { role: 'system', content: QA_SYSTEM_PREFIX + 't' },
      { role: 'user', content: 'first question' },
    ]);
  });

  it('cuts a transcript over the budget as a summary does', () => {
    const transcript = Array.from({ length: 50 }, (_, i) => `[00:${String(i).padStart(2, '0')}] A: line ${i}`).join('\n');
    expect(questionMessages(transcript, 200, [], 'q')[0].content).toContain('characters omitted');
  });

  it('joins adjacent excerpts and marks gaps', () => {
    const chunk = (line: number): TranscriptChunk => ({ text: `line ${line}`, startMs: line * 1000, endMs: (line + 1) * 1000, firstLine: line, lastLine: line });
    const history: LlmMessage[] = [
      { role: 'user', content: 'q1' },
      { role: 'assistant', content: 'a1' },
    ];
    const messages = questionFromExcerptsMessages({ chunks: [chunk(1), chunk(2), chunk(7)], coveredMs: 3000, totalMs: 10_000 }, history, '  why?  ');
    expect(messages).toEqual([
      { role: 'system', content: QA_EXCERPTS_PREFIX + 'line 1\nline 2' + EXCERPT_GAP + 'line 7' },
      ...history,
      { role: 'user', content: 'why?' },
    ]);
  });
});

describe('coverageMinutes', () => {
  it('rounds and keeps the covered minutes within the total', () => {
    expect(coverageMinutes(18 * 60_000 + 10_000, 91 * 60_000 + 40_000)).toEqual([18, 92]);
    expect(coverageMinutes(5000, 90 * 60_000)).toEqual([1, 90]);
  });
});
