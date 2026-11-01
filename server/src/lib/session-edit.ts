import { createHash } from 'node:crypto';
import { conflict, getDocumentMeta, putDocument, readManifest, type DocumentMeta } from './documents';
import { HttpError } from './http';
import { clean, editText, trim, type Token } from './token-aligner';
import { KNOWN_SCHEMA } from './transcript-export';

/** One change to a session from the web app (#84). `at` is an index in the manifest's array. */
export type SessionEdit =
  | { kind: 'title'; title: string }
  | { kind: 'tags'; tags: string[] }
  | { kind: 'speaker'; index: number; label: string }
  | { kind: 'line'; at: number; text: string }
  | { kind: 'actionDone'; at: number; done: boolean }
  | { kind: 'action'; at: number; text: string; owner: string | null; dueDate: string | null }
  | { kind: 'actionDelete'; at: number };

type Json = Record<string, unknown>;

const TITLE_MAX = 100;
const TAG_MAX = 30;

const isRecord = (value: unknown): value is Json => !!value && typeof value === 'object' && !Array.isArray(value);
const isIndex = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const isString = (value: unknown): value is string => typeof value === 'string';
const isOptional = (value: unknown): value is string | null => value === null || isString(value);

/** A request's edit, or a 400. */
export function parseEdit(value: unknown): SessionEdit {
  const e = isRecord(value) ? value : {};
  const ok =
    (e.kind === 'title' && isString(e.title)) ||
    (e.kind === 'tags' && Array.isArray(e.tags) && e.tags.every(isString)) ||
    (e.kind === 'speaker' && isIndex(e.index) && isString(e.label)) ||
    (e.kind === 'line' && isIndex(e.at) && isString(e.text)) ||
    (e.kind === 'actionDone' && isIndex(e.at) && typeof e.done === 'boolean') ||
    (e.kind === 'action' && isIndex(e.at) && isString(e.text) && isOptional(e.owner) && isOptional(e.dueDate)) ||
    (e.kind === 'actionDelete' && isIndex(e.at));
  if (!ok) throw new HttpError(400, 'invalid_body');
  return e as SessionEdit;
}

/** The app's `TagNames.normalise`: trimmed, whitespace collapsed, capped, or null when blank. */
function tagName(name: string): string | null {
  return trim(clean(name).slice(0, TAG_MAX)) || null;
}

/** The app's `TagNames.clean`, in the order the app lists a session's tags: by lower-case name. */
function tagNames(names: string[]): string[] {
  const byKey = new Map<string, string>();
  for (const name of names) {
    const tag = tagName(name);
    if (tag && !byKey.has(tag.toLowerCase())) byKey.set(tag.toLowerCase(), tag);
  }
  return [...byKey.keys()].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0)).map((key) => byKey.get(key)!);
}

/** The app's `ActionItemsParser.isoDate`: the first `yyyy-mm-dd` in `text`, if it is a real date. */
function isoDate(text: string): string | null {
  const match = /\b(\d{4})-(\d{2})-(\d{2})\b/.exec(text);
  if (!match) return null;
  const [year, month, day] = match.slice(1).map(Number);
  const leap = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const days = [31, leap ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31][month - 1];
  return days !== undefined && day >= 1 && day <= days ? match[0] : null;
}

const tokensOf = (line: Json): Token[] =>
  (Array.isArray(line.tokens) ? line.tokens : []).filter(
    (t): t is Token => isRecord(t) && typeof t.ms === 'number' && isString(t.text),
  );

/** The element at `at` of the manifest's array `name`; a 400 when there is none. */
function element(manifest: Json, name: string, at: number): Json {
  const items = manifest[name];
  const item = Array.isArray(items) ? items[at] : undefined;
  if (!isRecord(item)) throw new HttpError(400, 'invalid_edit');
  return item;
}

/**
 * Apply `edit` to a parsed manifest in place, by the app's rules for the same
 * edit (`SessionRepository`, `TranscriptEditor`). Only the fields those rules
 * name are touched, so whatever else a newer app wrote stays. Returns whether
 * anything changed: a blank or repeated value changes nothing, as in the app.
 */
export function applyEdit(manifest: Json, edit: SessionEdit): boolean {
  switch (edit.kind) {
    case 'title': {
      const title = trim(trim(edit.title).slice(0, TITLE_MAX));
      if (!title) return false;
      if (manifest.title === title && manifest.titleSource === 'user' && (manifest.previousTitle ?? null) === null) return false;
      manifest.title = title;
      manifest.titleSource = 'user';
      manifest.previousTitle = null;
      return true;
    }
    case 'tags': {
      const tags = tagNames(edit.tags);
      if (JSON.stringify(tags) === JSON.stringify(manifest.tags ?? [])) return false;
      manifest.tags = tags;
      return true;
    }
    case 'speaker': {
      const label = trim(edit.label);
      const speakers: unknown[] = Array.isArray(manifest.speakers) ? manifest.speakers : [];
      const speaker = speakers.find((s): s is Json => isRecord(s) && s.index === edit.index);
      if (!label || !speaker || speaker.label === label) return false;
      speaker.label = label;
      // Full notes name the speakers.
      for (const mark of Array.isArray(manifest.marks) ? manifest.marks : []) {
        if (isRecord(mark) && mark.expandedNote !== null && mark.expandedNote !== undefined) mark.expandedStale = true;
      }
      return true;
    }
    case 'line': {
      const line = element(manifest, 'lines', edit.at);
      if (!isString(line.text)) throw new HttpError(400, 'invalid_edit');
      const startMs = typeof line.startMs === 'number' ? line.startMs : 0;
      const endMs = typeof line.endMs === 'number' ? line.endMs : startMs;
      const edited = editText({ startMs, endMs, text: line.text, tokens: tokensOf(line) }, edit.text);
      if (!edited) return false;
      line.text = edited.text;
      line.tokens = edited.tokens;
      line.textEdited = true;
      manifest.transcriptEdited = true;
      return true;
    }
    case 'actionDone': {
      const item = element(manifest, 'actionItems', edit.at);
      if ((item.done === true) === edit.done) return false;
      item.done = edit.done;
      return true;
    }
    case 'action': {
      const item = element(manifest, 'actionItems', edit.at);
      const text = trim(edit.text);
      if (!text) return false;
      const owner = (edit.owner !== null && trim(edit.owner)) || null;
      const dueDate = edit.dueDate !== null ? isoDate(edit.dueDate) : null;
      if (item.text === text && (item.owner ?? null) === owner && (item.dueDate ?? null) === dueDate) return false;
      Object.assign(item, { text, owner, dueDate, edited: true });
      return true;
    }
    case 'actionDelete': {
      const item = element(manifest, 'actionItems', edit.at);
      if (item.deleted === true) return false;
      item.deleted = true;
      return true;
    }
  }
}

/**
 * Edit a session from the web app (#84), as one more device would: a new
 * revision of the manifest through [putDocument], which devices download.
 * Only from the revision the page showed: 409 if it has changed since. 422
 * `newer_schema` for a manifest from a newer app than this server knows, 404
 * for an unknown or deleted session. An edit that changes nothing returns the
 * current revision.
 *
 * The manifest is patched as plain JSON rather than typed, so a field this
 * server has never heard of survives. (JSON numbers become doubles on the
 * way, which no field the app writes is large enough to notice.)
 */
export async function editSession(owner: string, syncId: string, baseRevision: number, edit: SessionEdit): Promise<DocumentMeta> {
  const current = await getDocumentMeta(owner, 'session', syncId);
  if (!current || current.deleted || !current.manifestHash) throw new HttpError(404, 'not_found');
  if (current.revision !== baseRevision) throw conflict(current);
  const bytes = await readManifest(owner, 'session', syncId, current.manifestHash);
  let manifest: unknown;
  try {
    manifest = JSON.parse(Buffer.from(bytes).toString('utf8'));
  } catch {
    throw new HttpError(422, 'invalid_manifest');
  }
  if (!isRecord(manifest)) throw new HttpError(422, 'invalid_manifest');
  const { schema } = manifest;
  if (schema !== undefined && (typeof schema !== 'number' || schema > KNOWN_SCHEMA)) throw new HttpError(422, 'newer_schema');
  if (!applyEdit(manifest, edit)) return current;
  const next = Buffer.from(JSON.stringify(manifest));
  return putDocument(
    owner,
    'session',
    syncId,
    {
      manifest: next,
      manifestHash: createHash('sha256').update(next).digest('hex'),
      baseRevision,
      durationMs: current.durationMs,
      // Later than what it replaces even if a device's clock runs ahead, for the conflict rule.
      changedAt: Math.max(Date.now(), current.changedAt + 1),
    },
    { strict: true },
  );
}
