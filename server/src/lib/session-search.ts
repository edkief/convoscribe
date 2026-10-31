import { and, eq } from 'drizzle-orm';
import { sessionText } from '@/db/schema';
import type { Db } from './db';
import { chunks, cleanText, plainText, words } from './search-text';
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
