import { fold, words } from './search-text';

/**
 * A summary's action items (#117), ported from the app's `ActionItemsParser`
 * and `ActionItemMerge` (#31): the model ends a summary with a ```json block
 * of items, read leniently, else the bullets under its *Action items* heading
 * are read; then the items are folded into the session's, keeping what the
 * user did.
 */

export type ExtractedActionItem = { text: string; owner: string | null; due: string | null; atMs: number | null };

export type ParsedSummary = { prose: string; items: ExtractedActionItem[]; fromJson: boolean };

const FENCE = '```';
const TIME = /\b((?:\d{1,2}:)?\d{1,2}:\d{2})\b/;
const BRACKETED_TIME = /\[((?:\d{1,2}:)?\d{1,2}:\d{2})]/;
const ISO_DATE = /\b(\d{4}-\d{2}-\d{2})\b/;
const BULLET = /^\s*(?:[-*•+]|\d{1,2}[.)])\s+(.*)$/;
const HEADING_MARKUP = /^[\s#*_>]*(?:\d{1,2}[.)]\s*)?[\s*_]*/u;
const NAME = "\\p{Lu}[\\p{L}.'-]*(?: \\p{Lu}[\\p{L}.'-]*){0,2}";
const LEADING_OWNER = new RegExp(`^\\**(${NAME})\\**\\s*(?::|—|–| - )\\s*(.+)$`, 'u');
const TRAILING_OWNER = new RegExp(`^(.+?)\\s*\\((?:[\\p{L} ]{1,12}:\\s*)?(${NAME})\\)\\s*\\.?$`, 'u');
const MARK_BULLET = /^\s*[-*•+]\s/;
const NONE = new Set(['none', 'nothing', 'n a', 'na', 'aucun', 'aucune', 'neant', 'rien']);
const MAX_HEADING_CHARS = 40;

/** The folded words of `text` in order, as the app's `SearchText.terms`. */
const termsOf = (text: string) => [...fold(text).matchAll(/[\p{L}\p{N}]+/gu)].map((m) => m[0]);

/** `text` as the user reads it and its action items: the JSON block when it decodes, else the *Action items* bullets. */
export function splitSummary(text: string, knownNames: string[] = []): ParsedSummary {
  const block = findBlock(text);
  const prose = (block ? text.slice(0, block.start) : text).trimEnd();
  const fromJson = block ? decode(block.body, knownNames) : null;
  return fromJson ? { prose, items: fromJson, fromJson: true } : { prose, items: fromBullets(prose, knownNames), fromJson: false };
}

/** What of a summary still streaming in to show: everything before the JSON block, cut as soon as its fence starts. */
export function visibleSummary(streaming: string): string {
  const block = findBlock(streaming);
  if (block) return streaming.slice(0, block.start).trimEnd();
  const fence = streaming.lastIndexOf(FENCE);
  if (fence >= 0) {
    const rest = streaming.slice(fence + FENCE.length).trimStart();
    if (rest === '' || 'json'.startsWith(rest.toLowerCase())) return streaming.slice(0, fence).trimEnd();
  }
  const trimmed = streaming.replace(/`+$/, '');
  return trimmed.length < streaming.length ? trimmed.trimEnd() : streaming;
}

/** The last ```json block, or the last fenced block holding an array, up to its closing fence or the end. */
function findBlock(text: string): { start: number; body: string } | null {
  let index = text.lastIndexOf(FENCE);
  while (index >= 0) {
    const opener = text.slice(index + FENCE.length).trimStart();
    const isJson = opener.toLowerCase().startsWith('json');
    const body = isJson ? opener.slice(4) : opener;
    if (isJson || body.trimStart().startsWith('[')) {
      const end = body.indexOf(FENCE);
      return { start: index, body: end >= 0 ? body.slice(0, end) : body };
    }
    index = index === 0 ? -1 : text.lastIndexOf(FENCE, index - 1);
  }
  return null;
}

/** JSON as kotlinx's lenient mode reads it: keys may be unquoted. */
function parseLenient(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    try {
      return JSON.parse(text.replace(/([{,]\s*)([A-Za-z_][\w]*)\s*:/g, '$1"$2":'));
    } catch {
      return undefined;
    }
  }
}

function decode(body: string, knownNames: string[]): ExtractedActionItem[] | null {
  const element = parseLenient(body.trim());
  let array: unknown[] | undefined;
  if (Array.isArray(element)) array = element;
  else if (element && typeof element === 'object') array = Object.values(element).find(Array.isArray) as unknown[] | undefined;
  if (!array) return null;
  const items: ExtractedActionItem[] = [];
  for (const entry of array) {
    if (!entry || typeof entry !== 'object' || Array.isArray(entry)) continue;
    const item = entry as Record<string, unknown>;
    const text = clean(field(item, 'text', 'task', 'item', 'action'));
    if (!text) continue;
    const owner = field(item, 'owner', 'assignee', 'who');
    const due = field(item, 'due', 'due_date', 'dueDate');
    const at = field(item, 'at', 'timestamp', 'time');
    items.push({
      text,
      owner: owner !== null ? ownerName(owner, knownNames) : null,
      due: due !== null ? isoDate(due) : null,
      atMs: at !== null ? timeMs(at) : null,
    });
  }
  return items;
}

/** The first of `keys` holding a primitive, as trimmed text; null when blank or `null`. */
function field(item: Record<string, unknown>, ...keys: string[]): string | null {
  for (const key of keys) {
    const value = item[key];
    if (value === null || value === undefined || typeof value === 'object') continue;
    const text = String(value).trim();
    if (text && text !== 'null') return text;
  }
  return null;
}

function fromBullets(prose: string, knownNames: string[]): ExtractedActionItem[] {
  const lines = prose.split(/\r?\n/);
  let heading = -1;
  lines.forEach((line, i) => {
    const title = headingText(line);
    if (title !== null && isActionsHeading(title)) heading = i;
  });
  if (heading < 0) return [];
  const items: ExtractedActionItem[] = [];
  const inline = inlineContent(lines[heading]);
  if (inline) {
    const item = bulletItem(inline, knownNames);
    if (item) items.push(item);
  }
  for (const line of lines.slice(heading + 1)) {
    if (!line.trim()) continue;
    const bullet = BULLET.exec(line)?.[1];
    if (bullet === undefined || isSection(line)) break;
    const item = bulletItem(bullet, knownNames);
    if (item) items.push(item);
  }
  return items;
}

function headingText(line: string): string | null {
  if (MARK_BULLET.test(line)) return null;
  const bare = line.replace(HEADING_MARKUP, '').replaceAll('*', '').replaceAll('_', '').trim();
  const colon = bare.indexOf(':');
  const title = (colon >= 0 ? bare.slice(0, colon) : bare).trim();
  return title && title.length <= MAX_HEADING_CHARS ? title : null;
}

function isActionsHeading(title: string): boolean {
  const folded = fold(title);
  return folded.includes('action') || folded.includes('next step') || folded.includes('prochaine') || folded.includes('a faire');
}

function isSection(line: string): boolean {
  const trimmed = line.trim();
  if (trimmed.startsWith('#')) return true;
  if (MARK_BULLET.test(line)) return false;
  const bare = trimmed.replaceAll('*', '').trim();
  return bare.endsWith(':') && bare.length <= MAX_HEADING_CHARS;
}

function inlineContent(line: string): string | null {
  const bare = line.replaceAll('*', '');
  const colon = bare.indexOf(':');
  const rest = colon >= 0 ? bare.slice(colon + 1).trim() : '';
  return rest || null;
}

function bulletItem(raw: string, knownNames: string[]): ExtractedActionItem | null {
  let text = clean(raw);
  if (!text || isNone(text)) return null;
  let atMs: number | null = null;
  const bracketed = BRACKETED_TIME.exec(text);
  if (bracketed) {
    atMs = timeMs(bracketed[1]);
    if (atMs !== null) text = (text.slice(0, bracketed.index) + text.slice(bracketed.index + bracketed[0].length)).replaceAll('  ', ' ').trim();
  }
  const dateMatch = ISO_DATE.exec(text);
  const due = dateMatch ? isoDate(dateMatch[1]) : null;
  let owner: string | null = null;
  const leading = LEADING_OWNER.exec(text);
  if (leading) {
    owner = leading[1];
    text = leading[2];
  }
  if (owner === null) {
    const body = text;
    owner = [...knownNames].sort((a, b) => b.length - a.length).find((name) => mentions(body, name)) ?? null;
  }
  if (owner === null) {
    const trailing = TRAILING_OWNER.exec(text);
    if (trailing) {
      owner = trailing[2];
      text = trailing[1];
    }
  }
  text = text.trim().replace(/[,;]+$/, '').trim();
  if (!text) return null;
  return { text, owner: owner !== null ? ownerName(owner, knownNames) : null, due, atMs };
}

const clean = (text: string | null) => (text === null ? null : text.replaceAll('**', '').replaceAll('__', '').trim() || null);

const isNone = (text: string) => {
  const joined = termsOf(text).join(' ');
  return joined === '' || NONE.has(joined);
};

const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Whether `text` names `name` as a whole word, ignoring case and accents. */
function mentions(text: string, name: string): boolean {
  const wanted = fold(name.trim());
  if (!wanted) return false;
  return new RegExp(`(?<![\\p{L}\\p{N}])${escape(wanted)}(?![\\p{L}\\p{N}])`, 'u').test(fold(text));
}

/** `owner` as the session spells it when it is (or names) one of `knownNames`, else as written. */
export function ownerName(owner: string, knownNames: string[]): string | null {
  const trimmed = owner.trim().replace(/^\*+|\*+$/g, '').trim();
  if (!trimmed || isNone(trimmed) || fold(trimmed) === 'null') return null;
  const folded = fold(trimmed);
  return (
    knownNames.find((n) => fold(n.trim()) === folded) ??
    [...knownNames].sort((a, b) => b.length - a.length).find((n) => mentions(trimmed, n)) ??
    trimmed
  );
}

/** The first `yyyy-mm-dd` in `text` when it is a real date, else null. */
export function isoDate(text: string): string | null {
  const match = ISO_DATE.exec(text);
  if (!match) return null;
  const [year, month, day] = match[1].split('-').map(Number);
  const date = new Date(Date.UTC(year, month - 1, day));
  return date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day ? match[1] : null;
}

/** `[h:]mm:ss`, bracketed or not, in milliseconds; null when `text` holds no time. */
export function timeMs(text: string): number | null {
  const match = TIME.exec(text);
  if (!match) return null;
  return match[1].split(':').reduce((total, part) => total * 60 + Number(part), 0) * 1000;
}

/** Share of words two texts must have in common to be the same item. */
export const SAME_WORDS = 0.7;

/** Whether `a` and `b` name the same task: the same words ignoring case, accents and punctuation, or 70 % of them in common. */
export function sameItem(a: string, b: string): boolean {
  const left = new Set(words(a));
  const right = new Set(words(b));
  if (left.size === 0 || right.size === 0) return fold(a.trim()) === fold(b.trim());
  const common = [...left].filter((w) => right.has(w)).length;
  if (common === left.size && common === right.size) return true;
  const union = new Set([...left, ...right]).size;
  return common / union >= SAME_WORDS;
}

type Json = Record<string, unknown>;

const isRecord = (value: unknown): value is Json => !!value && typeof value === 'object' && !Array.isArray(value);

/** Whether the user did something to an item: ticked, edited or deleted it. */
const touched = (item: Json) => item.done === true || item.edited === true || item.deleted === true;

/**
 * The session's action items with a new summary's folded in (the app's
 * `ActionItemMerge.merge`): items the user ticked, edited or deleted stay as
 * they are, the model's others are replaced, and a new item the same as a
 * kept one is left out, so nothing is listed twice and a deleted item does
 * not come back. New items follow the kept ones, in the summary's order.
 */
export function mergeActionItems(existing: unknown[], extracted: ExtractedActionItem[], now: number): Json[] {
  const kept = existing.filter((item): item is Json => isRecord(item) && touched(item));
  const seen = kept.flatMap((item) => [item.text, item.extractedText]).filter((t): t is string => typeof t === 'string');
  const added: Json[] = [];
  for (const item of extracted) {
    if (seen.some((s) => sameItem(s, item.text))) continue;
    seen.push(item.text);
    added.push({
      text: item.text,
      extractedText: item.text,
      owner: item.owner,
      dueDate: item.due,
      atMs: item.atMs,
      done: false,
      edited: false,
      deleted: false,
      createdAt: now,
    });
  }
  return [...kept, ...added];
}
