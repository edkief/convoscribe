import { timeMs } from './action-items';
import type { LlmMessage } from './llm-client';
import { SUMMARY_GUARDRAILS, fit, transcriptText, type PromptLine, type PromptMark } from './summary-prompt';
import { markLineIndex, timestamp } from './transcript-export';

/*
 * The app's smaller language-model prompts (#119), ported from
 * `PromptBuilder`, `TitleCleaner`, `TalkingPointAnswer` and `MarkWindow`
 * with the prompt text word for word.
 */

// --- Session title (the app's #32) ---

/** The app's `PromptBuilder.TITLE_SYSTEM`; [titleMessages] adds the language. */
export const TITLE_SYSTEM =
  'You name recorded conversations. Reply with a title of at most 6 words that tells this ' +
  'conversation apart from others: its main topic, not the kind of meeting. ' +
  'No quotes, no punctuation at the end, nothing else.';

/** How much of the summary goes into a title request; the opening is what matters. */
export const TITLE_SUMMARY_MAX_CHARS = 4000;

/**
 * Messages for a session title, in `language` (`fr` French, anything else
 * English): from its summary, as the app's `PromptBuilder.title`, or, for a
 * session without one (#119, not in the app), from its transcript fitted to
 * the same budget.
 */
export function titleMessages(from: { summary: string } | { transcript: string }, language: string | null): LlmMessage[] {
  const name = language === 'fr' ? 'French' : 'English';
  const user = 'summary' in from ? 'Summary:\n\n' + from.summary.trim().slice(0, TITLE_SUMMARY_MAX_CHARS) : 'Transcript:\n\n' + fit(from.transcript, TITLE_SUMMARY_MAX_CHARS);
  return [
    { role: 'system', content: `${TITLE_SYSTEM} Write it in ${name}.` },
    { role: 'user', content: user },
  ];
}

/** Words a title keeps (the app's `TitleCleaner.MAX_WORDS`). */
export const TITLE_MAX_WORDS = 6;

/** The app's `SessionRepository.SESSION_TITLE_MAX_LEN`. */
export const TITLE_MAX_LEN = 100;

const TITLE_LABEL = /^(title|titre)\s*:\s*/i;
const TITLE_MARKUP = /^[#>*_`\-\s]+|[*_`]+/g;
const QUOTES = '"\'“”‘’«»`';
const TRAILING = '.,;:!?-–— ';

function trimWhere(text: string, drop: (c: string) => boolean, start: boolean, end: boolean): string {
  const chars = [...text];
  let from = 0;
  let to = chars.length;
  while (start && from < to && drop(chars[from])) from++;
  while (end && to > from && drop(chars[to - 1])) to--;
  return chars.slice(from, to).join('');
}

/**
 * The model's answer to [titleMessages] as a session title (the app's
 * `TitleCleaner.clean`): the first non-blank line, without a "Title:" label,
 * Markdown, surrounding quotes or a trailing full stop, at most
 * [TITLE_MAX_WORDS] words and [TITLE_MAX_LEN] characters. Null when nothing
 * usable is left.
 */
export function cleanTitle(raw: string): string | null {
  let line = raw
    .split(/\r?\n|\r/)
    .map((l) => l.trim())
    .find((l) => l !== '');
  if (line === undefined) return null;
  line = line.replace(TITLE_MARKUP, '');
  line = line.replace(TITLE_LABEL, '');
  line = trimWhere(line, (c) => /\s/.test(c) || QUOTES.includes(c), true, true);
  const words = line
    .split(/\s+/)
    .filter((w) => w !== '')
    .slice(0, TITLE_MAX_WORDS);
  const title = trimWhere(words.join(' ').slice(0, TITLE_MAX_LEN), (c) => TRAILING.includes(c) || QUOTES.includes(c), false, true).trim();
  return /[\p{L}\p{N}]/u.test(title) ? title : null;
}

// --- Mark notes (the app's #48) ---

/** The app's `PromptBuilder.MARK_NOTE_SYSTEM`. */
export const MARK_NOTE_SYSTEM =
  'You turn a short note the user jotted while recording a conversation into a full note, ' +
  'using the part of the transcript around the moment they marked. ' +
  'Write in the same language as the transcript. ' +
  'Write 1 to 4 sentences or short bullet points about what was said at that moment, ' +
  'focused on what the user\'s note points at, with no heading or introduction. ' +
  SUMMARY_GUARDRAILS;

/** Stands in for the note of a mark that has none (the app's `MARK_NOTE_WITHOUT_NOTE`). */
export const MARK_NOTE_WITHOUT_NOTE = 'The user left no note: say what was being discussed at that moment.';

/** How far before and after a mark its window reaches (the app's `MarkWindow`). */
export const MARK_BEFORE_MS = 60_000;
export const MARK_AFTER_MS = 30_000;

/**
 * The whole `lines` that overlap `atMs - beforeMs .. atMs + afterMs`, in
 * order, always with the line being spoken at `atMs` (the app's
 * `MarkWindow.lines`). Empty without lines. `lines` must be in time order.
 */
export function markWindow<L extends Pick<PromptLine, 'startMs' | 'endMs'>>(lines: L[], atMs: number, beforeMs = MARK_BEFORE_MS, afterMs = MARK_AFTER_MS): L[] {
  const at = markLineIndex(lines, atMs);
  if (at === null) return [];
  const from = atMs - beforeMs;
  const to = atMs + afterMs;
  return lines.filter((line, index) => index === at || (Math.max(line.endMs, line.startMs) >= from && line.startMs <= to));
}

/**
 * Messages for the full note of `mark` (the app's `PromptBuilder.markNote`):
 * its time and note, and the `window` of transcript around it fitted to
 * `maxChars`.
 */
export function markNoteMessages(mark: PromptMark, window: PromptLine[], maxChars: number): LlmMessage[] {
  const note = mark.note?.trim() ? mark.note : null;
  const ask = note === null ? MARK_NOTE_WITHOUT_NOTE : `The user's note: "${note}"`;
  return [
    { role: 'system', content: MARK_NOTE_SYSTEM },
    { role: 'user', content: `Marked at [${timestamp(mark.atMs)}]. ${ask}\n\nTranscript around it:\n\n` + fit(transcriptText(window), maxChars) },
  ];
}

// --- Talking points (the app's #103) ---

/** The app's `PromptBuilder.TALKING_POINTS_SYSTEM`. */
export const TALKING_POINTS_SYSTEM =
  'You follow a conversation as it is being recorded and keep track of the points the user means ' +
  'to raise. You get the numbered points not covered yet and the latest part of the transcript. ' +
  'A point is covered only when the transcript actually discusses it, not when a word merely ' +
  'appears. Reply with only a JSON array, one object per covered point, giving its number and the ' +
  'time of the transcript line where it is discussed, for example ' +
  '[{"point": 2, "at": "12:34"}]. Reply [] when none is covered. Nothing else.';

const TALKING_POINTS_FRAME_CHARS = 200;
const TALKING_POINTS_MIN_TRANSCRIPT_CHARS = 1000;

const pointsList = (points: string[]) => points.map((p, i) => `${i + 1}. ${p}`).join('\n');

/** How much transcript a check of `points` may send within `maxChars` in all (the app's `talkingPointsTranscriptBudget`). */
export function talkingPointsBudget(points: string[], maxChars: number): number {
  const overhead = TALKING_POINTS_SYSTEM.length + TALKING_POINTS_FRAME_CHARS + pointsList(points).length;
  return Math.max(maxChars - overhead, TALKING_POINTS_MIN_TRANSCRIPT_CHARS);
}

/** Messages for a talking-point check of the open `points` against `transcript` (the app's `PromptBuilder.talkingPoints`). */
export function talkingPointsMessages(points: string[], transcript: string): LlmMessage[] {
  return [
    { role: 'system', content: TALKING_POINTS_SYSTEM },
    { role: 'user', content: 'Points not covered yet:\n' + pointsList(points) + '\n\nLatest transcript:\n\n' + transcript },
  ];
}

/** A point the model says was covered: its number in the prompt (from 1), and when, if it said. */
export type CoveredPoint = { number: number; atMs: number | null };

const THINK = /<think>[\s\S]*?<\/think>/gi;

/** A JSON value, also when its object keys aren't quoted, as the app's lenient reader takes it. */
function lenientJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    try {
      return JSON.parse(text.replace(/([{,]\s*)([A-Za-z_]\w*)\s*:/g, '$1"$2":'));
    } catch {
      return undefined;
    }
  }
}

const intOf = (value: unknown): number | null => {
  if (typeof value === 'number') return Number.isSafeInteger(value) ? value : null;
  if (typeof value === 'string' && /^[+-]?\d+$/.test(value.trim())) return Number(value.trim());
  return null;
};

function coveredPoint(element: unknown): CoveredPoint | null {
  if (element === null || typeof element !== 'object') {
    const number = intOf(element);
    return number === null ? null : { number, atMs: null };
  }
  if (Array.isArray(element)) return null;
  const e = element as Record<string, unknown>;
  const number = intOf(e.point);
  if (number === null) return null;
  const at = typeof e.at === 'string' || typeof e.at === 'number' ? timeMs(String(e.at)) : null;
  return { number, atMs: at };
}

/**
 * The points `answer` says were covered, of `count` asked about (the app's
 * `TalkingPointAnswer.parse`): a JSON array read leniently, with a reasoning
 * block, code fence or prose around it, bare or quoted numbers. Numbers
 * outside `1..count` and repeats are dropped. An answer with no array and no
 * digit ("None of them.") is nothing covered; null when unreadable.
 */
export function parseCoveredPoints(answer: string, count: number): CoveredPoint[] | null {
  let text = answer.replace(THINK, '');
  // A reasoning block whose start was cut off or never closed.
  const closing = text.toLowerCase().lastIndexOf('</think>');
  if (closing >= 0) text = text.slice(closing + '</think>'.length);
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start < 0 || end < start) return !/\d/.test(text) && !/<think>/i.test(text) ? [] : null;
  const array = lenientJson(text.slice(start, end + 1));
  if (!Array.isArray(array)) return null;
  const seen = new Set<number>();
  return array
    .map(coveredPoint)
    .filter((p): p is CoveredPoint => p !== null && p.number >= 1 && p.number <= count && !seen.has(p.number) && !!seen.add(p.number));
}
