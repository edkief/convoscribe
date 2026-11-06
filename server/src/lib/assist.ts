import { cleanTitle, markNoteMessages, markWindow, parseCoveredPoints, talkingPointsBudget, talkingPointsMessages, titleMessages } from './assist-prompt';
import type { LlmMessage } from './llm-client';
import type { Chat } from './summarise';
import { chunkTranscript, transcriptText, type PromptLine } from './summary-prompt';
import { sessionView, spoken } from './transcript-export';

/*
 * The smaller language-model actions on a session from the web app (#119):
 * a title, a check of the talking points, and the full notes of marks, run as
 * the app's `AutoTitler`, `TalkingPointChecker` and `MarkNotesWriter` do, each
 * applied to a manifest held as plain JSON so fields this server doesn't know
 * stay as they are.
 */

type Json = Record<string, unknown>;

const isRecord = (value: unknown): value is Json => !!value && typeof value === 'object' && !Array.isArray(value);

async function collect(chat: Chat, messages: LlmMessage[], signal?: AbortSignal) {
  let text = '';
  for await (const piece of chat(messages, signal)) text += piece;
  return text;
}

/** A session's spoken lines, in time order, as the prompts take them. */
export function promptLines(manifest: Json): PromptLine[] {
  return spoken(sessionView(manifest)).map(({ startMs, endMs, speaker, text }) => ({ startMs, endMs, speaker, text }));
}

// --- Title ---

/**
 * What a session's title is written from: its latest summary, else (not in
 * the app) its transcript; null when it has neither.
 */
export function titleSource(manifest: Json): { from: { summary: string } | { transcript: string }; language: string | null } | null {
  const view = sessionView(manifest);
  const summary = view.summaries.at(-1)?.content;
  const language = view.language;
  if (summary?.trim()) return { from: { summary }, language };
  const lines = promptLines(manifest);
  return lines.length ? { from: { transcript: transcriptText(lines) }, language } : null;
}

/** The model's title for `source` ([titleMessages], [cleanTitle]), or null when its answer holds none. */
export async function suggestTitle(source: NonNullable<ReturnType<typeof titleSource>>, chat: Chat, signal?: AbortSignal): Promise<string | null> {
  return cleanTitle(await collect(chat, titleMessages(source.from, source.language), signal));
}

/**
 * Give a session the title the model wrote after its summary, as the app's
 * `SessionRepository.applyAutoTitle`: only a title still the date-based
 * default is replaced, and kept as `previousTitle` to put back. Returns
 * whether anything changed.
 */
export function applyAutoTitle(manifest: Json, title: string): boolean {
  if (manifest.titleSource !== 'default' || manifest.title === title) return false;
  manifest.previousTitle = typeof manifest.title === 'string' ? manifest.title : null;
  manifest.title = title;
  manifest.titleSource = 'auto';
  return true;
}

/**
 * Give a session the title the user asked the model for (#119), whatever its
 * title was: written automatically, with the title it replaced kept to put
 * back. A title the model already wrote keeps the one from before it, so
 * *Put back* returns to the user's.
 */
export function applySuggestedTitle(manifest: Json, title: string): boolean {
  if (manifest.title === title) return false;
  const keep = manifest.titleSource === 'auto' && typeof manifest.previousTitle === 'string';
  if (!keep) manifest.previousTitle = typeof manifest.title === 'string' ? manifest.title : null;
  manifest.title = title;
  manifest.titleSource = 'auto';
  return true;
}

// --- Talking points ---

/** A point the model may tick: neither covered nor set by hand. */
export type OpenPoint = { id: string; text: string };

/** A session's points the model may tick, in order. */
export function openPoints(manifest: Json): OpenPoint[] {
  const points = Array.isArray(manifest.talkingPoints) ? manifest.talkingPoints : [];
  return points
    .filter(isRecord)
    .filter((p) => typeof p.id === 'string' && typeof p.text === 'string' && p.covered !== true && p.byUser !== true)
    .map((p) => ({ id: p.id as string, text: p.text as string }));
}

export type CheckInput = {
  lines: PromptLine[];
  points: OpenPoint[];
  maxChars: number;
  chat: Chat;
  signal?: AbortSignal;
  /** Called before each part of a transcript too long to check at once. */
  onStep?: (step: { part: number; parts: number }) => void;
};

/**
 * Which of `points` the whole transcript covers, and when, as the app's
 * `TalkingPointChecker` asks while recording: the transcript goes in parts
 * that fit the budget, each with the points still open, and the points the
 * model names in a part ([parseCoveredPoints]) are ticked at the time it
 * gives if that falls within the part, else at its last line. Returns the
 * ticks by point id, or null for an answer that can't be read.
 */
export async function checkPoints({ lines, points, maxChars, chat, signal, onStep }: CheckInput): Promise<Map<string, number> | null> {
  const ticks = new Map<string, number>();
  const parts = chunkTranscript(lines, talkingPointsBudget(points.map((p) => p.text), maxChars));
  for (const [index, part] of parts.entries()) {
    const open = points.filter((p) => !ticks.has(p.id));
    if (!open.length) break;
    if (parts.length > 1) onStep?.({ part: index + 1, parts: parts.length });
    const answer = await collect(chat, talkingPointsMessages(open.map((p) => p.text), part.text), signal);
    const covered = parseCoveredPoints(answer, open.length);
    if (!covered) {
      console.warn(`web talking points: unreadable answer: ${answer.slice(0, 100)}`);
      return null;
    }
    const last = lines[part.lastLine];
    const endMs = Math.max(last.endMs, last.startMs);
    for (const point of covered) {
      const atMs = point.atMs !== null && point.atMs >= part.startMs && point.atMs <= endMs ? point.atMs : last.startMs;
      ticks.set(open[point.number - 1].id, atMs);
    }
  }
  return ticks;
}

/**
 * Tick the points in `ticks` at their times, as the app's
 * `SessionRepository.tickTalkingPointsAutomatically`: a point covered
 * already, or ticked or unticked by hand, is left as it is. Returns how many
 * were ticked.
 */
export function applyTicks(manifest: Json, ticks: Map<string, number>): number {
  let ticked = 0;
  for (const p of Array.isArray(manifest.talkingPoints) ? manifest.talkingPoints : []) {
    if (!isRecord(p) || typeof p.id !== 'string' || !ticks.has(p.id) || p.covered === true || p.byUser === true) continue;
    Object.assign(p, { covered: true, coveredAtMs: ticks.get(p.id), byUser: false });
    ticked++;
  }
  return ticked;
}

// --- Mark notes ---

/** A mark whose full note is to be written: `at` is its index in the manifest's marks. */
export type MarkToWrite = { at: number; atMs: number; note: string | null };

/** A session's marks with no full note, or one written before the transcript changed (#119). */
export function marksToWrite(manifest: Json): MarkToWrite[] {
  const marks = Array.isArray(manifest.marks) ? manifest.marks : [];
  return marks.flatMap((m, at) =>
    isRecord(m) && typeof m.atMs === 'number' && (typeof m.expandedNote !== 'string' || m.expandedStale === true)
      ? [{ at, atMs: m.atMs, note: typeof m.note === 'string' ? m.note : null }]
      : [],
  );
}

export type NotesInput = {
  lines: PromptLine[];
  marks: MarkToWrite[];
  maxChars: number;
  chat: Chat;
  signal?: AbortSignal;
  /** Called once each mark's note is written: `done` of `total`. */
  onProgress?: (progress: { done: number; total: number }) => void;
};

/**
 * The full note of each of `marks`, as the app's `MarkNotesWriter` writes it:
 * from the mark's note and the transcript around it ([markWindow],
 * [markNoteMessages]), one call at a time. Returns the notes by mark index; a
 * blank answer writes nothing for its mark.
 */
export async function writeMarkNotes({ lines, marks, maxChars, chat, signal, onProgress }: NotesInput): Promise<Map<number, string>> {
  const notes = new Map<number, string>();
  for (const [done, mark] of marks.entries()) {
    const text = (await collect(chat, markNoteMessages(mark, markWindow(lines, mark.atMs), maxChars), signal)).trim();
    if (text) notes.set(mark.at, text);
    onProgress?.({ done: done + 1, total: marks.length });
  }
  return notes;
}

/**
 * Save the full notes into the manifest's marks, as the app's
 * `SessionRepository.setMarkExpansion`: current again, written at `now`.
 * Returns how many were saved.
 */
export function applyMarkNotes(manifest: Json, notes: Map<number, string>, now: number): number {
  const marks = Array.isArray(manifest.marks) ? manifest.marks : [];
  let saved = 0;
  for (const [at, text] of notes) {
    const mark = marks[at];
    if (!isRecord(mark)) continue;
    Object.assign(mark, { expandedNote: text, expandedAt: now, expandedStale: false });
    saved++;
  }
  return saved;
}
