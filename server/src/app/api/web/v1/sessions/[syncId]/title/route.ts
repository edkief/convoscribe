import { assertService } from '@/lib/access';
import { upstreamOf } from '@/lib/ai-proxy';
import { applySuggestedTitle, suggestTitle, titleSource } from '@/lib/assist';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream } from '@/lib/llm-client';
import { ndjsonResponse } from '@/lib/ndjson';
import { loadForEdit, saveEdited } from '@/lib/session-edit';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/title'>;

/** One line of the answer's stream. */
type Event = { done: true; revision: number; title: string } | { error: string };

/**
 * *Suggest title* (#119): the server's language model names the session from
 * its summary, else its transcript, as the app's `AutoTitler` does, and the
 * title replaces the current one, which is kept (`previousTitle`) to put
 * back. The JSON body is `{ "baseRevision": n }`. A request that can't start
 * is a JSON error; once started, the answer is NDJSON: `done` with the saved
 * revision and the title, or `error`. Closing the request saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const baseRevision = baseRevisionOf(await jsonBody(req, 4096));
    if (!upstreamOf('llm')) throw new HttpError(404, 'not_offered');
    await assertService(owner, 'llm');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const source = titleSource(editable.manifest);
    if (!source) throw new HttpError(422, 'empty_transcript');

    return ndjsonResponse<Event>(req, 'web title', async (send, signal) => {
      const title = await suggestTitle(source, chatStream, signal);
      if (signal.aborted) return;
      if (!title) {
        send({ error: 'empty_reply' });
        return;
      }
      const changed = applySuggestedTitle(editable.manifest, title);
      const saved = changed ? await saveEdited(owner, syncId, editable) : editable.current;
      send({ done: true, revision: saved.revision, title });
    });
  });
}
