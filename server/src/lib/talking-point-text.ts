/**
 * How talking points are written down, as pure functions a client
 * component can import too: the app's `TalkingPoints` (#104, #114).
 */
import { fold } from './search-text';

/** Longest point or name kept, in characters: the app's `TalkingPoints.MAX_LEN`. */
export const POINT_MAX = 300;

/**
 * The app's `TalkingPoints.clean`: on one line, whitespace collapsed,
 * trimmed and capped at [POINT_MAX]. NUL goes too, which Postgres text
 * cannot hold.
 */
export function cleanPoint(text: string): string {
  return text
    .replace(/\u0000/g, '')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, POINT_MAX)
    .trim();
}

/** The app's `TalkingPoints.cleanAll`: each cleaned, blanks left out. */
export const cleanPoints = (texts: string[]): string[] => texts.map(cleanPoint).filter((p) => p !== '');

const LINE_BREAK = /\r\n|\r|\n/;

/** The app's `TalkingPoints` drops the same markers: a bullet or number, then a checkbox, each followed by a space. */
const MARKER = /^\s*(?:(?:[-*+•·]|\d{1,3}[.)])\s+)?(?:\[[ xX]\]\s+)?/;

const key = (text: string) => fold(cleanPoint(text));

/** Whether `text` runs over more than one line, as pasted text can (#114). */
export const isMultiLine = (text: string) => LINE_BREAK.test(text);

/** The app's `TalkingPoints.append`: `extra` cleaned and after `current`, leaving out repeats (ignoring case and accents). */
export function appendPoints(current: string[], extra: string[]): string[] {
  const seen = new Set(current.map(key));
  const out = [...current];
  for (const point of cleanPoints(extra)) {
    const k = key(point);
    if (seen.has(k)) continue;
    seen.add(k);
    out.push(point);
  }
  return out;
}

/**
 * The app's `TalkingPoints.fromPaste` (#114): a pasted block as points,
 * one per line, with a leading list marker (`-`, `*`, `+`, `•`, `·`,
 * `1.`, `1)`) and checkbox (`[ ]`, `[x]`) dropped, each cleaned, and
 * blanks and repeats left out. `-5 degrees` keeps its sign.
 */
export const pointsFromPaste = (text: string): string[] =>
  appendPoints(
    [],
    text.split(LINE_BREAK).map((line) => line.replace(MARKER, '')),
  );

/** The points `text` adds to `current`: [pointsFromPaste], leaving out those `current` already has. */
export const pastedPoints = (current: string[], text: string): string[] => appendPoints(current, pointsFromPaste(text)).slice(current.length);

/**
 * Rows after pasting several lines into row `at` (#114): the row keeps
 * the first point no other row has, and the rest go right after it.
 */
export function pasteIntoRow(rows: string[], at: number, text: string): string[] {
  const fresh = pastedPoints(
    rows.filter((_, i) => i !== at),
    text,
  );
  return [...rows.slice(0, at), fresh[0] ?? '', ...fresh.slice(1), ...rows.slice(at + 1)];
}

/** Rows after importing a pasted block (#114): its points after the existing ones, filling blank rows at the end. */
export function importIntoRows(rows: string[], text: string): string[] {
  let end = rows.length;
  while (end > 0 && rows[end - 1].trim() === '') end--;
  const kept = rows.slice(0, end);
  const out = [...kept, ...pastedPoints(kept, text)];
  return out.length ? out : [''];
}
