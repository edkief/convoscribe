import { mergeActionItems, visibleSummary, type ExtractedActionItem } from './action-items';
import type { LlmMessage } from './llm-client';
import {
  chunkTranscript,
  mergeNotesMessages,
  notesText,
  summaryFromPartsMessages,
  summaryMessages,
  summaryPartMessages,
  transcriptText,
  type PartNotes,
  type PromptLine,
  type PromptMark,
  type RecordedOn,
  type SummaryTemplate,
} from './summary-prompt';
import { templateRef } from './summary-templates';
import { isoDate } from './format';
import { sessionView, spoken } from './transcript-export';

/** Streams a model's reply to `messages`, as [chatStream] does. */
export type Chat = (messages: LlmMessage[], signal?: AbortSignal) => AsyncIterable<string>;

/** What a summary in parts is doing (the app's `SummaryStep`). */
export type SummaryStep = { part: number; parts: number } | 'combining' | 'writing';

/** Rounds of combining notes before they are fitted to the budget as they are (the app's `LongSummariser.MAX_MERGE_ROUNDS`). */
export const MAX_MERGE_ROUNDS = 4;

export type SummariseInput = {
  lines: PromptLine[];
  marks: PromptMark[];
  maxChars: number;
  body: string;
  recordedOn: RecordedOn | null;
  chat: Chat;
  signal?: AbortSignal;
  onStep?: (step: SummaryStep) => void;
  /** The summary written so far, without its action-item block, after each piece. */
  onText?: (visible: string) => void;
};

async function collect(chat: Chat, messages: LlmMessage[], signal?: AbortSignal) {
  let text = '';
  for await (const piece of chat(messages, signal)) text += piece;
  return text;
}

/**
 * The model's whole reply for a session's summary, action-item block
 * included, as the app's `SummaryViewModel.summarise` gets it: one call for a
 * transcript within `maxChars`, else notes on each part, combined while they
 * are still too long, then the summary from them (`LongSummariser`). Only the
 * final call's text reaches `onText`.
 */
export async function summarise({ lines, marks, maxChars, body, recordedOn, chat, signal, onStep, onText }: SummariseInput): Promise<string> {
  let reply = '';
  const stream = async (messages: LlmMessage[]) => {
    for await (const piece of chat(messages, signal)) {
      reply += piece;
      onText?.(visibleSummary(reply));
    }
  };
  const transcript = transcriptText(lines);
  if (transcript.length <= maxChars) {
    await stream(summaryMessages(transcript, maxChars, body, marks, recordedOn));
    return reply;
  }

  const chunks = chunkTranscript(lines, maxChars);
  let notes: PartNotes[] = [];
  for (const [index, chunk] of chunks.entries()) {
    onStep?.({ part: index + 1, parts: chunks.length });
    const inChunk = marks.filter((m) => (index === 0 || m.atMs >= chunk.startMs) && (index === chunks.length - 1 || m.atMs < chunk.endMs));
    notes.push({ startMs: chunk.startMs, endMs: chunk.endMs, text: await collect(chat, summaryPartMessages(chunk, index + 1, chunks.length, inChunk), signal) });
  }

  for (let round = 0; round < MAX_MERGE_ROUNDS && notesText(notes).length > maxChars; round++) {
    const groups = group(notes, maxChars);
    if (groups.length === notes.length) break;
    onStep?.('combining');
    const merged: PartNotes[] = [];
    for (const g of groups) {
      merged.push(g.length === 1 ? g[0] : { startMs: g[0].startMs, endMs: g[g.length - 1].endMs, text: await collect(chat, mergeNotesMessages(g), signal) });
    }
    if (notesText(merged).length >= notesText(notes).length) break;
    notes = merged;
  }

  onStep?.('writing');
  await stream(summaryFromPartsMessages(notes, maxChars, body, marks, recordedOn));
  return reply;
}

/** Consecutive runs of `notes` whose text fits `maxChars`; a note too long alone is a run of one. */
function group(notes: PartNotes[], maxChars: number): PartNotes[][] {
  const groups: PartNotes[][] = [];
  let current: PartNotes[] = [];
  for (const note of notes) {
    if (current.length && notesText([...current, note]).length > maxChars) {
      groups.push(current);
      current = [];
    }
    current.push(note);
  }
  if (current.length) groups.push(current);
  return groups;
}

type Json = Record<string, unknown>;

/** What a summary is made from, read from a session manifest. */
export function summarySource(manifest: Json, zone: string) {
  const view = sessionView(manifest);
  return {
    lines: spoken(view).map(({ startMs, endMs, speaker, text }) => ({ startMs, endMs, speaker, text })),
    marks: view.marks.map(({ atMs, note }) => ({ atMs, note })),
    knownNames: [...new Set([...view.speakers.map((s) => s.label), ...view.participants].filter((n) => n.trim()))],
    recordedOn: view.createdAt !== null ? recordedOn(view.createdAt, zone) : null,
    language: view.language,
  };
}

/** The day `ms` fell on in `zone`, with its English weekday, as the app tells the model. */
export function recordedOn(ms: number, zone: string): RecordedOn {
  return { date: isoDate(ms, zone), weekday: new Intl.DateTimeFormat('en-US', { weekday: 'long', timeZone: zone }).format(ms) };
}

/**
 * Save a summary into a session manifest, as the app's `summarise` does: the
 * session's summaries replaced by this one with its template's name, its
 * action items merged in ([mergeActionItems]), and the template kept for the
 * next summary. Fields this server doesn't know are left alone.
 */
export function applySummary(manifest: Json, { prose, items, template, now }: { prose: string; items: ExtractedActionItem[]; template: SummaryTemplate; now: number }) {
  const messages = Array.isArray(manifest.messages) ? manifest.messages : [];
  manifest.messages = [
    ...messages.filter((m) => !(m && typeof m === 'object' && (m as Json).kind === 'SUMMARY')),
    { kind: 'SUMMARY', role: 'ASSISTANT', content: prose, createdAt: now, templateName: template.name },
  ];
  const existing = Array.isArray(manifest.actionItems) ? manifest.actionItems : [];
  const merged = mergeActionItems(existing, items, now);
  if (merged.length || manifest.actionItems !== undefined) manifest.actionItems = merged;
  manifest.summaryTemplate = templateRef(template);
}
