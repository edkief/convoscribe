import { upstreamOf } from '@/lib/ai-proxy';
import { applyMarkNotes, marksToWrite, promptLines, writeMarkNotes } from '@/lib/assist';
import { env } from '@/lib/env';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream } from '@/lib/llm-client';
import { ndjsonResponse } from '@/lib/ndjson';
import { loadForEdit, saveEdited } from '@/lib/session-edit';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/mark-notes'>;

/** One line of the answer's stream. */
type Event = { progress: { done: number; total: number } } | { done: true; revision: number; written: number } | { error: string };

/**
 * *Write notes* (#119): the server's language model writes the full note of
 * each mark that has none, or one written before the transcript changed,
 * from the transcript around it, as the app's `MarkNotesWriter` does. The
 * JSON body is `{ "baseRevision": n }`. A request that can't start is a JSON
 * error; once started, the answer is NDJSON: `progress` as each note is
 * written, then `done` with the revision that saved them all, or `error`.
 * Closing the request saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const baseRevision = baseRevisionOf(await jsonBody(req, 4096));
    if (!upstreamOf('llm')) throw new HttpError(404, 'not_offered');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const marks = marksToWrite(editable.manifest);
    if (marks.length === 0) throw new HttpError(422, 'no_marks');
    const lines = promptLines(editable.manifest);
    if (lines.length === 0) throw new HttpError(422, 'empty_transcript');

    return ndjsonResponse<Event>(req, 'web mark notes', async (send, signal) => {
      send({ progress: { done: 0, total: marks.length } });
      const notes = await writeMarkNotes({
        lines,
        marks,
        maxChars: env().LLM_MAX_TRANSCRIPT_CHARS,
        chat: chatStream,
        signal,
        onProgress: (progress) => send({ progress }),
      });
      if (signal.aborted) return;
      if (notes.size === 0) {
        send({ error: 'empty_reply' });
        return;
      }
      const written = applyMarkNotes(editable.manifest, notes, Date.now());
      const saved = await saveEdited(owner, syncId, editable);
      send({ done: true, revision: saved.revision, written });
    });
  });
}
