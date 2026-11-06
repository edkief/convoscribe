import { describe, expect, it } from 'vitest';
import { answerQuestion, applyQa, questionSource } from './ask';
import type { LlmMessage } from './llm-client';
import { QA_EXCERPTS_PREFIX } from './qa-prompt';
import type { PromptLine } from './summary-prompt';

function fakeChat(text: string) {
  const calls: LlmMessage[][] = [];
  const chat = async function* (messages: LlmMessage[]) {
    calls.push(messages);
    for (let i = 0; i < text.length; i += 4) yield text.slice(i, i + 4);
  };
  return { chat, calls };
}

const filler = (i: number): PromptLine => ({ startMs: i * 60_000, endMs: i * 60_000 + 50_000, speaker: 'Alice', text: `we talked about the weather again, part ${i}.` });

describe('answerQuestion', () => {
  it('sends a transcript within the budget whole, streaming the answer', async () => {
    const { chat, calls } = fakeChat('It was sunny.');
    const shown: string[] = [];
    const result = await answerQuestion({ lines: [filler(0)], history: [], question: 'Weather?', maxChars: 24_000, chat, onText: (t) => shown.push(t) });
    expect(result).toEqual({ answer: 'It was sunny.', excerpts: null });
    expect(shown.at(-1)).toBe('It was sunny.');
    expect(calls[0]).toHaveLength(2);
  });

  it('searches with the previous question too, for a follow-up', async () => {
    const lines = Array.from({ length: 40 }, (_, i) => (i === 30 ? { ...filler(i), speaker: 'Bob', text: 'the Kowalski invoice is overdue.' } : filler(i)));
    const { chat, calls } = fakeChat('Bob.');
    const history: LlmMessage[] = [
      { role: 'user', content: 'What about Kowalski?' },
      { role: 'assistant', content: 'It is overdue.' },
    ];
    const { excerpts } = await answerQuestion({ lines, history, question: 'Who said that?', maxChars: 600, chat });
    expect(calls[0][0].content.startsWith(QA_EXCERPTS_PREFIX)).toBe(true);
    expect(calls[0][0].content).toContain('Kowalski invoice');
    expect(excerpts!.chunks.some((c) => c.firstLine <= 30 && c.lastLine >= 30)).toBe(true);
    expect(calls[0].slice(1)).toEqual([...history, { role: 'user', content: 'Who said that?' }]);
  });
});

describe('questionSource', () => {
  it('reads the spoken lines and the Q&A, not the summaries, as turns', () => {
    const source = questionSource({
      lines: [
        { startMs: 0, endMs: 1000, text: 'Hi.', speakerIndex: 0 },
        { startMs: 1000, endMs: 2000, text: ' ', speakerIndex: 0 },
      ],
      speakers: [{ index: 0, label: 'Ann' }],
      messages: [
        { kind: 'SUMMARY', role: 'ASSISTANT', content: 's', createdAt: 1 },
        { kind: 'QA', role: 'USER', content: 'q', createdAt: 2 },
        { kind: 'QA', role: 'ASSISTANT', content: 'a', createdAt: 3 },
      ],
    });
    expect(source.lines).toEqual([{ startMs: 0, endMs: 1000, speaker: 'Ann', text: 'Hi.' }]);
    expect(source.history).toEqual([
      { role: 'user', content: 'q' },
      { role: 'assistant', content: 'a' },
    ]);
  });
});

describe('applyQa', () => {
  it('appends the question and answer, the answer with its coverage, after every message there', () => {
    const manifest: Record<string, unknown> = { messages: [{ kind: 'QA', role: 'ASSISTANT', content: 'a', createdAt: 5000 }] };
    applyQa(manifest, { question: 'q', answer: 'b', askedAt: 4000, now: 4000, excerpts: { chunks: [], coveredMs: 60_000, totalMs: 600_000 } });
    expect((manifest.messages as unknown[]).slice(1)).toEqual([
      { kind: 'QA', role: 'USER', content: 'q', createdAt: 5001 },
      { kind: 'QA', role: 'ASSISTANT', content: 'b', createdAt: 5002, coveredMs: 60_000, totalMs: 600_000 },
    ]);
  });

  it('leaves the coverage out for an answer from the whole transcript, and makes the list when there is none', () => {
    const manifest: Record<string, unknown> = {};
    applyQa(manifest, { question: 'q', answer: 'b', askedAt: 10, now: 20, excerpts: null });
    expect(manifest.messages).toEqual([
      { kind: 'QA', role: 'USER', content: 'q', createdAt: 10 },
      { kind: 'QA', role: 'ASSISTANT', content: 'b', createdAt: 20 },
    ]);
  });
});
