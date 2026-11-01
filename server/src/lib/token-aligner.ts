/**
 * The app's rules for editing a transcript line's text (#84): `TokenAligner.kt`
 * and `TranscriptEditor.clean`/`editText`, so an edit on the web gives what the
 * same edit gives on a device. `token-aligner.cases.json` is asserted by this
 * port's tests and by the app's `TokenAlignerCasesTest`, so the two can't
 * quietly diverge. Where the JVM and JavaScript differ, the JVM is followed.
 */

export type Token = { ms: number; text: string };

// Java's `\s` is ASCII only, where JavaScript's is all of Unicode.
const WHITESPACE = /[ \t\n\x0B\f\r]+/g;
const WORD = /[^ \t\n\x0B\f\r]+/g;
// Kotlin's `Char.isWhitespace`, which `trim()` uses; not JavaScript's `trim()`.
const EDGE = /^[\p{Zs}\p{Zl}\p{Zp}\t\n\x0B\f\r\x1C-\x1F]+|[\p{Zs}\p{Zl}\p{Zp}\t\n\x0B\f\r\x1C-\x1F]+$/gu;
const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u;

/** Kotlin's `String.trim()`. */
export const trim = (text: string) => text.replace(EDGE, '');

/** `text` as a transcript line stores it: trimmed, one space between words. */
export const clean = (text: string) => trim(text).replace(WHITESPACE, ' ');

const isWordStart = (token: Token) => token.text.startsWith(' ') || token.text.startsWith('▁');

/** Tokens grouped into words: a new word starts at each word-start token. */
function words(tokens: Token[]): Token[][] {
  const out: Token[][] = [];
  for (const token of tokens) {
    if (out.length === 0 || isWordStart(token)) out.push([token]);
    else out[out.length - 1].push(token);
  }
  return out;
}

/** Lower case, letters and digits only; punctuation-only words keep their characters. */
function key(word: string): string {
  const cleaned = trim(word.replaceAll('▁', ' '));
  let letters = '';
  // By UTF-16 unit, as Kotlin's `filter` goes: a letter outside the BMP doesn't count.
  for (let i = 0; i < cleaned.length; i++) {
    if (LETTER_OR_DIGIT.test(cleaned[i])) letters += cleaned[i];
  }
  return (letters || cleaned).toLowerCase();
}

/** Index pairs (old, new) of one longest common subsequence of `a` and `b`, in order. */
function commonSubsequence(a: string[], b: string[]): [number, number][] {
  const lengths = Array.from({ length: a.length + 1 }, () => new Int32Array(b.length + 1));
  for (let i = a.length - 1; i >= 0; i--) {
    for (let j = b.length - 1; j >= 0; j--) {
      lengths[i][j] = a[i] === b[j] ? lengths[i + 1][j + 1] + 1 : Math.max(lengths[i + 1][j], lengths[i][j + 1]);
    }
  }
  const pairs: [number, number][] = [];
  let i = 0;
  let j = 0;
  while (i < a.length && j < b.length) {
    if (a[i] === b[j]) pairs.push([i++, j++]);
    else if (lengths[i + 1][j] >= lengths[i][j + 1]) i++;
    else j++;
  }
  return pairs;
}

/**
 * Give each untimed word a time between its timed neighbours. A run at the
 * start of the line begins at `startMs`; any other run starts after the timed
 * word before it.
 */
function fillGaps(times: (number | null)[], startMs: number, endMs: number) {
  let i = 0;
  while (i < times.length) {
    if (times[i] !== null) {
      i++;
      continue;
    }
    const runStart = i;
    while (i < times.length && times[i] === null) i++;
    const count = i - runStart;
    const atLineStart = runStart === 0;
    const left = atLineStart ? startMs : times[runStart - 1]!;
    const right = Math.max(left, i < times.length ? times[i]! : endMs);
    const slots = atLineStart ? count : count + 1;
    for (let k = 0; k < count; k++) {
      const step = atLineStart ? k : k + 1;
      times[runStart + k] = left + Math.trunc(((right - left) * step) / slots);
    }
  }
}

/**
 * Tokens for `newText` re-timed from `oldTokens` within `[startMs, endMs]`:
 * one per word, a word that stayed keeping its time and the others spread
 * between their neighbours. Empty when `oldTokens` is empty (the line had no
 * timings).
 */
export function align(oldTokens: Token[], newText: string, startMs: number, endMs: number): Token[] {
  if (oldTokens.length === 0) return [];
  const newWords = newText.match(WORD) ?? [];
  if (newWords.length === 0) return [];
  const oldWords = words(oldTokens);
  const oldKeys = oldWords.map((word) => key(word.map((t) => t.text).join('')));
  const times: (number | null)[] = newWords.map(() => null);
  for (const [oldIndex, newIndex] of commonSubsequence(oldKeys, newWords.map(key))) {
    times[newIndex] = oldWords[oldIndex][0].ms;
  }
  fillGaps(times, startMs, Math.max(startMs, endMs));
  return newWords.map((word, i) => ({ ms: times[i]!, text: ` ${word}` }));
}

/**
 * A line's new text and tokens for an edit to `text`; null when the new text
 * is blank or the same as before.
 */
export function editText(
  line: { startMs: number; endMs: number; text: string; tokens: Token[] },
  text: string,
): { text: string; tokens: Token[] } | null {
  const cleaned = clean(text);
  if (cleaned === '' || cleaned === line.text) return null;
  return { text: cleaned, tokens: align(line.tokens, cleaned, line.startMs, line.endMs) };
}
