import { and, desc, eq, gte, lt, sql } from 'drizzle-orm';
import { blobs, documents, sessionIndex } from '@/db/schema';
import { db, type Db } from './db';

/** A session as the library lists it (#80). */
export type SessionListItem = {
  syncId: string;
  title: string | null;
  createdAt: number;
  durationMs: number | null;
  language: string | null;
  status: string | null;
  tags: string[];
  speakerCount: number | null;
  speakers: string[];
  preview: string | null;
  openActions: number;
  lineCount: number | null;
};

/** The index row of a session, as stored. */
export type SessionIndexRow = typeof sessionIndex.$inferSelect;

type FileRef = { sha256: string; size: number };

const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
// Postgres text cannot hold NUL, which JSON strings can.
const withoutNul = (value: string) => value.replace(/\u0000/g, '');
const str = (value: unknown, max = 500) => (typeof value === 'string' ? withoutNul(value).slice(0, max) : null);
const int = (value: unknown) => (typeof value === 'number' && Number.isSafeInteger(value) ? value : null);
const fileRef = (value: unknown): FileRef | null => {
  const ref = record(value);
  return typeof ref.sha256 === 'string' && /^[0-9a-f]{64}$/.test(ref.sha256) && int(ref.size) !== null
    ? { sha256: ref.sha256, size: ref.size as number }
    : null;
};

/** Markdown as one line of plain text: headings, tables and code dropped, markers removed. */
function plainText(markdown: string): string {
  return markdown
    .split('\n')
    .filter((line) => !/^\s*(#{1,6}\s|```|\|?\s*:?-{3,}|[-*_]{3,}\s*$)/.test(line))
    .map((line) =>
      line
        .replace(/^\s*(?:[-*+]|\d+[.)]|>)\s+/, '')
        .replace(/\|/g, ' ')
        .replace(/[*_`]+/g, '')
        .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1'),
    )
    .join(' ')
    .replace(/\s+/g, ' ')
    .trim();
}

const PREVIEW_CHARS = 280;

/** The start of the session's latest summary, else of its transcript, as plain text. */
function preview(m: Record<string, unknown>): string | null {
  const messages = Array.isArray(m.messages) ? m.messages.map(record) : [];
  const summary = messages.filter((x) => x.kind === 'SUMMARY' && x.role === 'ASSISTANT' && typeof x.content === 'string').at(-1);
  const lines = Array.isArray(m.lines) ? m.lines.map(record) : [];
  const text = summary
    ? plainText(summary.content as string)
    : lines
        .map((l) => (typeof l.text === 'string' ? l.text : ''))
        .join(' ')
        .slice(0, PREVIEW_CHARS * 2)
        .replace(/\s+/g, ' ')
        .trim();
  return text ? withoutNul(text).slice(0, PREVIEW_CHARS) : null;
}

/**
 * What the index keeps of a session manifest. Tolerant by design: a field
 * the manifest lacks or has in a shape this server does not know is null
 * (`createdAt` falls back to `fallbackCreatedAt`), so a push never fails
 * on it and a newer app's manifest still lists.
 */
export function indexValues(manifest: unknown, fallbackCreatedAt: number) {
  const m = record(manifest);
  const tags = Array.isArray(m.tags)
    ? [
        ...new Set(
          m.tags
            .filter((t): t is string => typeof t === 'string')
            .map((t) => withoutNul(t).slice(0, 200))
            .filter((t) => t.trim() !== ''),
        ),
      ]
    : [];
  const speakerLabels = Array.isArray(m.speakers)
    ? m.speakers
        .map(record)
        .filter((sp) => typeof sp.label === 'string' && typeof sp.index === 'number')
        .sort((a, b) => (a.index as number) - (b.index as number))
        .map((sp) => withoutNul(sp.label as string).slice(0, 100))
        .slice(0, 20)
    : [];
  const speakers = Array.isArray(m.speakers) ? m.speakers.length : null;
  const openActions = Array.isArray(m.actionItems)
    ? m.actionItems.map(record).filter((a) => a.deleted !== true && a.done !== true && typeof a.text === 'string').length
    : 0;
  const audio = fileRef(m.audio);
  const clean = fileRef(m.cleanAudio);
  return {
    title: str(m.title),
    createdAt: int(m.createdAt) ?? fallbackCreatedAt,
    durationMs: int(m.durationMs),
    language: str(m.language, 32),
    status: str(m.status, 64),
    tags,
    speakerCount: speakers || int(m.numSpeakers),
    speakers: speakerLabels,
    preview: preview(m),
    openActions,
    lineCount: Array.isArray(m.lines) ? m.lines.length : null,
    audioSha256: audio?.sha256 ?? null,
    audioSize: audio?.size ?? null,
    cleanAudioSha256: clean?.sha256 ?? null,
    cleanAudioSize: clean?.size ?? null,
  };
}

/** Write a session's index row from its manifest; runs inside the push's transaction. */
export async function upsertSessionIndex(
  tx: Pick<Db, 'insert'>,
  owner: string,
  syncId: string,
  manifestHash: string,
  manifest: unknown,
  fallbackCreatedAt: number,
) {
  const values = { ...indexValues(manifest, fallbackCreatedAt), manifestHash, indexedAt: new Date() };
  await tx
    .insert(sessionIndex)
    .values({ owner, syncId, ...values })
    .onConflictDoUpdate({ target: [sessionIndex.owner, sessionIndex.syncId], set: values });
}

/** A session's index row, or null. */
export async function getSessionIndex(owner: string, syncId: string): Promise<SessionIndexRow | null> {
  const [row] = await db()
    .select()
    .from(sessionIndex)
    .where(and(eq(sessionIndex.owner, owner), eq(sessionIndex.syncId, syncId)));
  return row ?? null;
}

/** Where a page of the library starts: just after this session. */
export type Cursor = { createdAt: number; syncId: string };

export const encodeCursor = (c: Cursor) => `${c.createdAt}.${c.syncId}`;

export function decodeCursor(value: string | undefined): Cursor | undefined {
  const match = /^(-?\d{1,15})\.([0-9A-Za-z-]{1,64})$/.exec(value ?? '');
  return match ? { createdAt: Number(match[1]), syncId: match[2] } : undefined;
}

export type SessionFilter = {
  /** Only sessions with this tag (exactly as listed by [listTags]). */
  tag?: string;
  /** Recorded at or after, ms since epoch. */
  from?: number;
  /** Recorded before, ms since epoch. */
  to?: number;
  after?: Cursor;
  limit?: number;
};

const live = and(
  eq(documents.owner, sessionIndex.owner),
  eq(documents.kind, 'session'),
  eq(documents.syncId, sessionIndex.syncId),
  eq(documents.deleted, false),
);

/**
 * An owner's live sessions, newest first, `limit` at a time; `next` is the
 * cursor of the following page, if there is one. Reads Postgres only.
 */
export async function listSessions(
  owner: string,
  filter: SessionFilter = {},
): Promise<{ sessions: SessionListItem[]; next?: Cursor }> {
  const limit = filter.limit ?? 50;
  const rows = await db()
    .select({
      syncId: sessionIndex.syncId,
      title: sessionIndex.title,
      createdAt: sessionIndex.createdAt,
      durationMs: sessionIndex.durationMs,
      language: sessionIndex.language,
      status: sessionIndex.status,
      tags: sessionIndex.tags,
      speakerCount: sessionIndex.speakerCount,
      speakers: sessionIndex.speakers,
      preview: sessionIndex.preview,
      openActions: sessionIndex.openActions,
      lineCount: sessionIndex.lineCount,
    })
    .from(sessionIndex)
    .innerJoin(documents, live)
    .where(
      and(
        eq(sessionIndex.owner, owner),
        filter.tag !== undefined ? sql`${sessionIndex.tags} @> array[${filter.tag}]::text[]` : undefined,
        filter.from !== undefined ? gte(sessionIndex.createdAt, filter.from) : undefined,
        filter.to !== undefined ? lt(sessionIndex.createdAt, filter.to) : undefined,
        filter.after
          ? sql`(${sessionIndex.createdAt}, ${sessionIndex.syncId}) < (${filter.after.createdAt}, ${filter.after.syncId})`
          : undefined,
      ),
    )
    .orderBy(desc(sessionIndex.createdAt), desc(sessionIndex.syncId))
    .limit(limit + 1);
  const sessions = rows.slice(0, limit);
  const last = sessions.at(-1);
  return { sessions, next: rows.length > limit && last ? { createdAt: last.createdAt, syncId: last.syncId } : undefined };
}

/** Every tag on an owner's live sessions with how many carry it, alphabetically. */
export async function listTags(owner: string): Promise<{ tag: string; count: number }[]> {
  const tag = sql<string>`unnest(${sessionIndex.tags})`;
  const rows = await db()
    .select({ tag, count: sql<number>`count(*)::int` })
    .from(sessionIndex)
    .innerJoin(documents, live)
    .where(eq(sessionIndex.owner, owner))
    .groupBy(tag);
  return rows.sort((a, b) => a.tag.localeCompare(b.tag, undefined, { sensitivity: 'base' }));
}

/** How many live sessions an owner has. */
export async function countSessions(owner: string): Promise<number> {
  const [row] = await db()
    .select({ count: sql<number>`count(*)::int` })
    .from(sessionIndex)
    .innerJoin(documents, live)
    .where(eq(sessionIndex.owner, owner));
  return row?.count ?? 0;
}

/** Bytes of recordings an owner has stored. */
export async function storageUsed(owner: string): Promise<number> {
  const [row] = await db()
    .select({ bytes: sql<string>`coalesce(sum(${blobs.size}), 0)` })
    .from(blobs)
    .where(eq(blobs.owner, owner));
  return Number(row?.bytes ?? 0);
}
