import { and, asc, desc, eq, inArray, lte, sql } from 'drizzle-orm';
import { documents, sessionIndex, sessionText } from '@/db/schema';
import { db, type Db } from './db';
import { chunks, cleanText, excerpt, highlight, plainText, terms, toTsQuery, words, type Segment } from './search-text';
import type { Cursor } from './session-index';
import { sessionView, spoken } from './transcript-export';

/**
 * What the current code writes to `session_text`. Raise it when that changes:
 * the backfill rewrites every session below it.
 */
export const SEARCH_TEXT_VERSION = 1;

const INSERT_BATCH = 500;

export type TextSource = 'title' | 'speaker' | 'summary' | 'line';

type TextRow = {
  source: TextSource;
  position: number;
  startMs: number | null;
  speaker: string | null;
  text: string;
};

/**
 * What a session's manifest makes searchable. A line's `position` is its
 * index among the session page's lines ([spoken]); a summary's counts back
 * from the latest. Never throws on an odd manifest.
 */
export function sessionTextRows(manifest: unknown): TextRow[] {
  const view = sessionView(manifest);
  const row = (source: TextSource, position: number, text: string, startMs: number | null = null, speaker: string | null = null): TextRow => ({
    source,
    position,
    startMs,
    speaker,
    text,
  });
  const title = (manifest as { title?: unknown } | null)?.title;
  return [
    ...(typeof title === 'string' ? [row('title', 0, title)] : []),
    ...view.speakers.map((s, i) => row('speaker', i, s.label)),
    ...[...view.summaries].reverse().map((m, i) => row('summary', i, plainText(m.content, { headings: true }))),
    ...spoken(view).map((line, i) => row('line', i, line.text, Math.round(line.startMs), line.speaker && cleanText(line.speaker))),
  ];
}

/** Replace what search holds for a session with what `manifest` says. */
async function replaceSessionText(tx: Pick<Db, 'delete' | 'insert'>, owner: string, syncId: string, manifest: unknown) {
  await deleteSessionText(tx, owner, syncId);
  const values = sessionTextRows(manifest).flatMap((row) =>
    chunks(cleanText(row.text)).flatMap((text, chunk) => {
      const found = words(text);
      return found.length ? [{ owner, syncId, ...row, chunk, text, words: found.join(' ') }] : [];
    }),
  );
  for (let i = 0; i < values.length; i += INSERT_BATCH) {
    await tx.insert(sessionText).values(values.slice(i, i + INSERT_BATCH));
  }
}

/** Search no longer finds a session, as when it goes to the trash; a restore writes it again. */
export const deleteSessionText = (tx: Pick<Db, 'delete'>, owner: string, syncId: string) =>
  tx.delete(sessionText).where(and(eq(sessionText.owner, owner), eq(sessionText.syncId, syncId)));

/**
 * Write a session's search text inside the push's transaction, in a savepoint
 * of its own: failing to index a session never fails its push. Returns the
 * `text_version` to record, 0 on failure so the backfill tries again.
 */
export async function writeSessionText(tx: Pick<Db, 'transaction'>, owner: string, syncId: string, manifest: unknown): Promise<number> {
  try {
    await tx.transaction((savepoint) => replaceSessionText(savepoint, owner, syncId, manifest));
    return SEARCH_TEXT_VERSION;
  } catch (err) {
    console.warn(`session search: could not index ${syncId} of ${owner}: ${err instanceof Error ? err.message : String(err)}`);
    return 0;
  }
}

/** The most matches of one kind a session returns; the rest are only counted. */
const MAX_MATCHES = 50;

export type LineMatch = { line: number; startMs: number; speaker: string | null; text: Segment[] };

/** A session search found, and what matched in it, marked. */
export type SearchMatch = {
  syncId: string;
  title: string | null;
  createdAt: number;
  durationMs: number | null;
  titleMatch: Segment[] | null;
  speakers: Segment[][];
  /** `position` counts back from the latest summary. */
  summaries: { position: number; text: Segment[] }[];
  /** In reading order. */
  lines: LineMatch[];
  /** Matching lines beyond those in `lines`. */
  moreLines: number;
};

/**
 * An owner's live sessions with every word of `query` in one place, newest
 * first and `limit` at a time, with what matched in each; `next` is the cursor
 * of the following page. Reads Postgres only. A query with no word in it finds
 * nothing.
 */
export async function searchSessions(
  owner: string,
  query: string,
  options: { after?: Cursor; limit?: number } = {},
): Promise<{ sessions: SearchMatch[]; next?: Cursor }> {
  const queryTerms = terms(query);
  if (queryTerms.length === 0) return { sessions: [] };
  const limit = options.limit ?? 20;
  const matches = sql`${sessionText.words} @@ ${toTsQuery(queryTerms)}::tsquery`;
  const page = await db()
    .select({
      syncId: sessionIndex.syncId,
      title: sessionIndex.title,
      createdAt: sessionIndex.createdAt,
      durationMs: sessionIndex.durationMs,
    })
    .from(sessionIndex)
    .innerJoin(
      documents,
      and(
        eq(documents.owner, sessionIndex.owner),
        eq(documents.kind, 'session'),
        eq(documents.syncId, sessionIndex.syncId),
        eq(documents.deleted, false),
      ),
    )
    .where(
      and(
        eq(sessionIndex.owner, owner),
        sql`exists (select 1 from ${sessionText} where ${sessionText.owner} = ${sessionIndex.owner} and ${sessionText.syncId} = ${sessionIndex.syncId} and ${matches})`,
        options.after
          ? sql`(${sessionIndex.createdAt}, ${sessionIndex.syncId}) < (${options.after.createdAt}, ${options.after.syncId})`
          : undefined,
      ),
    )
    .orderBy(desc(sessionIndex.createdAt), desc(sessionIndex.syncId))
    .limit(limit + 1);
  const sessions = page.slice(0, limit);
  const last = sessions.at(-1);
  const next = page.length > limit && last ? { createdAt: last.createdAt, syncId: last.syncId } : undefined;
  if (sessions.length === 0) return { sessions: [], next };

  const ranked = db()
    .select({
      syncId: sessionText.syncId,
      source: sessionText.source,
      position: sessionText.position,
      chunk: sessionText.chunk,
      startMs: sessionText.startMs,
      speaker: sessionText.speaker,
      text: sessionText.text,
      rank: sql<number>`row_number() over (partition by ${sessionText.syncId}, ${sessionText.source} order by ${sessionText.position}, ${sessionText.chunk})::int`.as('rank'),
      total: sql<number>`(count(*) over (partition by ${sessionText.syncId}, ${sessionText.source}))::int`.as('total'),
    })
    .from(sessionText)
    .where(
      and(
        eq(sessionText.owner, owner),
        inArray(
          sessionText.syncId,
          sessions.map((s) => s.syncId),
        ),
        matches,
      ),
    )
    .as('ranked');
  const found = await db().select().from(ranked).where(lte(ranked.rank, MAX_MATCHES)).orderBy(asc(ranked.position), asc(ranked.chunk));

  const bySession = new Map<string, SearchMatch>(
    sessions.map((s) => [s.syncId, { ...s, titleMatch: null, speakers: [], summaries: [], lines: [], moreLines: 0 }]),
  );
  for (const row of found) {
    const session = bySession.get(row.syncId)!;
    if (row.source === 'title') session.titleMatch = highlight(row.text, queryTerms);
    else if (row.source === 'speaker') session.speakers.push(highlight(row.text, queryTerms));
    else if (row.source === 'summary') session.summaries.push({ position: row.position, text: excerpt(row.text, queryTerms) });
    else {
      session.lines.push({ line: row.position, startMs: row.startMs ?? 0, speaker: row.speaker, text: excerpt(row.text, queryTerms) });
      session.moreLines = row.total - Math.min(row.total, MAX_MATCHES);
    }
  }
  return { sessions: [...bySession.values()], next };
}
