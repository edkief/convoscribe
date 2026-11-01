/**
 * Pure text helpers for full-text search in the web app (#83), following the
 * app's `SearchText` so a word finds the same things on both:
 *
 *  - words are runs of letters and digits ("l'équipe" is "l" and "équipe");
 *  - matching ignores case and accents ([fold]);
 *  - each typed word matches as a word prefix ("reun" finds "Réunion"), and
 *    every typed word must match in the same place.
 */

const WORD = /[\p{L}\p{N}]+/gu;

/** Longest word indexed; Postgres refuses a lexeme over 2 KB. */
const MAX_WORD_CHARS = 255;

/** How much of a query counts, so a pasted page cannot become a huge query. */
const MAX_QUERY_CHARS = 200;
const MAX_TERMS = 16;

/** The most text one row holds, far under the 1 MB a `tsvector` can hold. */
export const MAX_CHUNK_CHARS = 4000;

/** `text` lowercased with its diacritics removed: `Réunion` is `reunion`. */
export const fold = (text: string) => text.normalize('NFD').replace(/\p{Mn}+/gu, '').toLowerCase();

/** `text` as stored: composed, so an accent never splits a word, and without what Postgres text cannot hold. */
export const cleanText = (text: string) =>
  text
    .normalize('NFC')
    .replace(/\u0000/g, '')
    .replace(/\s+/g, ' ')
    .trim();

/** The distinct folded words of `text`, as `session_text.words` holds them. */
export function words(text: string): string[] {
  const found = new Set<string>();
  for (const [word] of text.normalize('NFC').matchAll(WORD)) {
    if (word.length <= MAX_WORD_CHARS) found.add(fold(word));
  }
  return [...found];
}

/** The folded words of a query, in order and without repeats. */
export const terms = (query: string) => words(query.slice(0, MAX_QUERY_CHARS)).slice(0, MAX_TERMS);

/**
 * `terms` as a `tsquery`: each one a prefix, all required. Terms hold only
 * letters and digits, so no operator or quote can get through.
 */
export const toTsQuery = (queryTerms: string[]) => queryTerms.map((term) => `'${term}':*`).join(' & ');

/**
 * `text` cut into pieces of at most `max` characters, at whitespace where
 * there is any, never inside a surrogate pair.
 */
export function chunks(text: string, max = MAX_CHUNK_CHARS): string[] {
  const out: string[] = [];
  let start = 0;
  while (text.length - start > max) {
    let end = text.lastIndexOf(' ', start + max);
    if (end <= start) {
      end = start + max;
      const code = text.charCodeAt(end - 1);
      if (code >= 0xd800 && code <= 0xdbff) end--;
    }
    out.push(text.slice(start, end));
    start = text[end] === ' ' ? end + 1 : end;
  }
  if (start < text.length) out.push(text.slice(start));
  return out;
}

/** A piece of text, to show marked or not; never HTML. */
export type Segment = { text: string; match: boolean };

/** Where in `text` a word starts with one of `queryTerms`. */
function matchRanges(text: string, queryTerms: string[]): [number, number][] {
  const ranges: [number, number][] = [];
  for (const found of text.matchAll(WORD)) {
    const word = fold(found[0]);
    if (queryTerms.some((term) => word.startsWith(term))) ranges.push([found.index, found.index + found[0].length]);
  }
  return ranges;
}

function segments(text: string, ranges: [number, number][], start = 0, end = text.length): Segment[] {
  const out: Segment[] = [];
  let at = start;
  for (const [from, to] of ranges) {
    if (from < at || to > end) continue;
    if (from > at) out.push({ text: text.slice(at, from), match: false });
    out.push({ text: text.slice(from, to), match: true });
    at = to;
  }
  if (at < end) out.push({ text: text.slice(at, end), match: false });
  return out;
}

/** `text` with the words `queryTerms` found marked. */
export const highlight = (text: string, queryTerms: string[]) => segments(text, matchRanges(text, queryTerms));

/**
 * `text` around its first match when it is long: about `before` characters
 * ahead of it and `after` from it, cut at spaces and marked with `…`. The
 * start of it when nothing in it matches.
 */
export function excerpt(text: string, queryTerms: string[], before = 80, after = 200): Segment[] {
  if (text.length <= before + after) return highlight(text, queryTerms);
  const ranges = matchRanges(text, queryTerms);
  const first = ranges[0]?.[0] ?? 0;
  let start = Math.max(first - before, 0);
  let end = Math.min(first + after, text.length);
  if (start > 0) {
    const space = text.indexOf(' ', start);
    if (space !== -1 && space < first) start = space + 1;
  }
  if (end < text.length) {
    const space = text.lastIndexOf(' ', end);
    if (space > first) end = space;
  }
  return [
    ...(start > 0 ? [{ text: '…', match: false }] : []),
    ...segments(text, ranges, start, end),
    ...(end < text.length ? [{ text: '…', match: false }] : []),
  ];
}

/**
 * Markdown as one line of plain text, with tables' rules and code fences
 * dropped and markers removed. Headings go too, unless `headings` keeps
 * their words.
 */
export function plainText(markdown: string, options: { headings?: boolean } = {}): string {
  return markdown
    .split('\n')
    .filter((line) => !/^\s*(```|\|?\s*:?-{3,}|[-*_]{3,}\s*$)/.test(line) && (options.headings || !/^\s*#{1,6}\s/.test(line)))
    .map((line) =>
      line
        .replace(/^\s*#{1,6}\s+/, '')
        .replace(/^\s*(?:[-*+]|\d+[.)]|>)\s+/, '')
        .replace(/\|/g, ' ')
        .replace(/[*_`]+/g, '')
        .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1'),
    )
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}
