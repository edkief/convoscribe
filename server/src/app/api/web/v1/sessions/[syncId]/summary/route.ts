import { splitSummary } from '@/lib/action-items';
import { assertService } from '@/lib/access';
import { upstreamOf } from '@/lib/ai-proxy';
import { applyAutoTitle, suggestTitle } from '@/lib/assist';
import { env } from '@/lib/env';
import { validZone } from '@/lib/format';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream } from '@/lib/llm-client';
import { ndjsonResponse, throttled } from '@/lib/ndjson';
import { loadForEdit, saveEdited } from '@/lib/session-edit';
import { applySummary, summarise, summarySource, type SummaryStep } from '@/lib/summarise';
import { listTemplates, resolveTemplate } from '@/lib/summary-templates';
import { templateKeyOf } from '@/lib/transcript-export';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/summary'>;

/** How often the summary written so far is sent while it streams in. */
const TEXT_EVERY_MS = 100;

/** One line of the answer's stream. */
type Event = { step: SummaryStep | 'naming' } | { text: string } | { done: true; revision: number } | { error: string };

/**
 * Summarise a session with the server's language model (#117), as the app
 * does. The JSON body is `{ "baseRevision": n, "template"?: key, "zone"?: tz }`:
 * the revision the page shows, a template key (else the session's, else
 * Meeting) and the browser's time zone, for the recording date the model is
 * told. A request that can't start is a JSON error; once started, the answer
 * is NDJSON events: `step` while a long transcript is summarised in parts,
 * `text` with the summary so far, `step: "naming"` while a session with its
 * date-based title is named from the summary (#119, the app's `AutoTitler`),
 * then `done` with the saved revision or `error`. Summary and title are saved
 * as one revision. Closing the request cancels the model and saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const body = await jsonBody(req, 4096);
    const baseRevision = baseRevisionOf(body);
    if (body.template !== undefined && body.template !== null && typeof body.template !== 'string') throw new HttpError(400, 'invalid_body');
    if (!upstreamOf('llm')) throw new HttpError(404, 'not_offered');
    await assertService(owner, 'llm');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const templates = await listTemplates(owner);
    const template = resolveTemplate((body.template as string | undefined) ?? templateKeyOf(editable.manifest.summaryTemplate), templates);
    const source = summarySource(editable.manifest, validZone(typeof body.zone === 'string' ? body.zone : undefined));
    if (source.lines.length === 0) throw new HttpError(422, 'empty_transcript');

    return ndjsonResponse<Event>(req, 'web summary', async (send, signal) => {
      const text = throttled((visible) => send({ text: visible }), TEXT_EVERY_MS);
      const reply = await summarise({
        ...source,
        maxChars: env().LLM_MAX_TRANSCRIPT_CHARS,
        body: template.body,
        chat: chatStream,
        signal,
        onStep: (step) => send({ step }),
        onText: text.text,
      });
      if (signal.aborted) return;
      text.flush();
      const { prose, items } = splitSummary(reply, source.knownNames);
      if (!prose) {
        send({ error: 'empty_reply' });
        return;
      }
      applySummary(editable.manifest, { prose, items, template, now: Date.now() });
      if (editable.manifest.titleSource === 'default') {
        send({ step: 'naming' });
        try {
          const title = await suggestTitle({ from: { summary: prose }, language: source.language }, chatStream, signal);
          if (title) applyAutoTitle(editable.manifest, title);
        } catch (err) {
          // As in the app, the session simply keeps its title.
          if (signal.aborted) return;
          console.warn(`web summary: naming the session failed: ${err instanceof Error ? err.message : String(err)}`);
        }
        if (signal.aborted) return;
      }
      const saved = await saveEdited(owner, syncId, editable);
      send({ done: true, revision: saved.revision });
    });
  });
}
