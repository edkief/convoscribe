import { describe, expect, it } from 'vitest';
import type { LlmMessage } from './llm-client';
import { applySummary, recordedOn, summarise, type SummaryStep } from './summarise';
import { BUILT_IN_TEMPLATES, MERGE_NOTES_SYSTEM, PART_NOTES_SYSTEM, summarySystem, type PromptLine } from './summary-prompt';

// The cases of the app's LongSummariserTest (#28), with a fake model.

function fakeChat(answer: (messages: LlmMessage[]) => string) {
  const calls: LlmMessage[][] = [];
  const chat = async function* (messages: LlmMessage[]) {
    calls.push(messages);
    const text = answer(messages);
    for (let i = 0; i < text.length; i += 7) yield text.slice(i, i + 7);
  };
  return { chat, calls };
}

const lines = (count: number): PromptLine[] =>
  Array.from({ length: count }, (_, i) => ({ startMs: i * 60_000, endMs: i * 60_000 + 50_000, speaker: 'Alice', text: `we agreed on fact${i} and then talked for a while about it.` }));

const notesFor = (messages: LlmMessage[]) => [...new Set(messages.at(-1)!.content.match(/fact\d+/g) ?? [])].join(' ');

const run = (chat: ReturnType<typeof fakeChat>['chat'], extra: Partial<Parameters<typeof summarise>[0]> = {}) =>
  summarise({ lines: lines(90), marks: [], maxChars: 1000, body: 'Body.', recordedOn: null, chat, ...extra });

describe('summarise', () => {
  it('sends a short transcript in one call, streaming what is visible', async () => {
    const { chat, calls } = fakeChat(() => 'Summary.\n```json\n[]\n```');
    const shown: string[] = [];
    const reply = await summarise({ lines: lines(2), marks: [], maxChars: 24_000, body: 'Body.', recordedOn: null, chat, onText: (t) => shown.push(t) });
    expect(calls).toHaveLength(1);
    expect(reply).toBe('Summary.\n```json\n[]\n```');
    expect(shown.at(-1)).toBe('Summary.');
  });

  it('takes notes on every part, then writes from all of them', async () => {
    const { chat, calls } = fakeChat((m) => (m[0].content === PART_NOTES_SYSTEM ? notesFor(m) : 'final summary'));
    const steps: SummaryStep[] = [];
    let shown = '';
    await run(chat, { onStep: (s) => steps.push(s), onText: (t) => (shown = t) });
    const parts = calls.filter((c) => c[0].content === PART_NOTES_SYSTEM).length;
    expect(parts).toBeGreaterThan(3);
    expect(calls).toHaveLength(parts + 1);
    expect(steps).toEqual([...Array.from({ length: parts }, (_, i) => ({ part: i + 1, parts })), 'writing']);
    const reduce = calls.at(-1)!;
    expect(reduce[0].content).toBe(summarySystem('Body.'));
    for (let i = 0; i < 90; i++) expect(reduce[1].content).toMatch(new RegExp(`\\bfact${i}\\b`));
    expect(shown).toBe('final summary');
  });

  it('combines notes that are still too long', async () => {
    const { chat, calls } = fakeChat((m) =>
      m[0].content === PART_NOTES_SYSTEM ? `${notesFor(m)} ${'x'.repeat(300)}` : m[0].content === MERGE_NOTES_SYSTEM ? notesFor(m) : 'final',
    );
    const steps: SummaryStep[] = [];
    await run(chat, { onStep: (s) => steps.push(s) });
    expect(steps).toContain('combining');
    const reduce = calls.at(-1)![1].content;
    expect(reduce.length).toBeLessThan(1500);
    for (let i = 0; i < 90; i++) expect(reduce).toMatch(new RegExp(`\\bfact${i}\\b`));
  });

  it('fits notes that do not shrink rather than looping forever', async () => {
    const { chat, calls } = fakeChat(() => 'y'.repeat(800));
    await run(chat);
    expect(calls.length).toBeLessThan(100);
    expect(calls.at(-1)![1].content).toContain('characters omitted');
  });

  it('gives each part its own marks and the summary all of them', async () => {
    const { chat, calls } = fakeChat(() => 'notes');
    await run(chat, { marks: [{ atMs: 30_000, note: 'early' }, { atMs: 85 * 60_000, note: 'late' }] });
    const parts = calls.slice(0, -1).map((c) => c.at(-1)!.content);
    expect(parts[0]).toContain('early');
    expect(parts[0]).not.toContain('late');
    expect(parts.at(-1)).toContain('late');
    expect(parts.filter((p) => p.includes('early'))).toHaveLength(1);
    const reduce = calls.at(-1)!.at(-1)!.content;
    expect(reduce).toContain('early');
    expect(reduce).toContain('late');
  });
});

describe('recordedOn', () => {
  it('is the day in the browser zone with its English weekday', () => {
    const ms = Date.UTC(2026, 9, 30, 23, 30);
    expect(recordedOn(ms, 'UTC')).toEqual({ date: '2026-10-30', weekday: 'Friday' });
    expect(recordedOn(ms, 'Europe/Paris')).toEqual({ date: '2026-10-31', weekday: 'Saturday' });
  });
});

describe('applySummary', () => {
  it('replaces the summaries, merges the items and keeps the template', () => {
    const manifest: Record<string, unknown> = { messages: [{ kind: 'SUMMARY', role: 'ASSISTANT', content: 'old' }, { kind: 'QA', role: 'USER', content: 'q' }] };
    applySummary(manifest, { prose: 'New.', items: [{ text: 'Ship', owner: null, due: null, atMs: null }], template: BUILT_IN_TEMPLATES[1], now: 7 });
    expect(manifest.messages).toEqual([
      { kind: 'QA', role: 'USER', content: 'q' },
      { kind: 'SUMMARY', role: 'ASSISTANT', content: 'New.', createdAt: 7, templateName: 'Interview' },
    ]);
    expect(manifest.actionItems).toEqual([expect.objectContaining({ text: 'Ship', createdAt: 7 })]);
    expect(manifest.summaryTemplate).toEqual({ key: 'interview' });
  });

  it('leaves out action items when there are none', () => {
    const manifest: Record<string, unknown> = {};
    applySummary(manifest, { prose: 'New.', items: [], template: BUILT_IN_TEMPLATES[0], now: 7 });
    expect(manifest.actionItems).toBeUndefined();
  });
});
