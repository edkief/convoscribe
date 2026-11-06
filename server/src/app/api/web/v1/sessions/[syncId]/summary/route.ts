import { splitSummary } from '@/lib/action-items';
import { upstreamOf } from '@/lib/ai-proxy';
import { env } from '@/lib/env';
import { validZone } from '@/lib/format';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream, LlmError } from '@/lib/llm-client';
import { loadForEdit, saveEdited } from '@/lib/session-edit';
import { applySummary, summarise, summarySource, type SummaryStep } from '@/lib/summarise';
import { listTemplates, resolveTemplate, templateKeyOf } from '@/lib/summary-templates';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/summary'>;

/** How often the summary written so far is sent while it streams in. */
const TEXT_EVERY_MS = 100;

/** One line of the answer's stream. */
type Event = { step: SummaryStep } | { text: string } | { done: true; revision: number } | { error: string };

/**
 * Summarise a session with the server's language model (#117), as the app
 * does. The JSON body is `{ "baseRevision": n, "template"?: key, "zone"?: tz }`:
 * the revision the page shows, a template key (else the session's, else
 * Meeting) and the browser's time zone, for the recording date the model is
 * told. A request that can't start is a JSON error; once started, the answer
 * is NDJSON events: `step` while a long transcript is summarised in parts,
 * `text` with the summary so far, then `done` with the saved revision or
 * `error`. Closing the request cancels the model and saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const body = await jsonBody(req, 4096);
    const baseRevision = baseRevisionOf(body);
    if (body.template !== undefined && body.template !== null && typeof body.template !== 'string') throw new HttpError(400, 'invalid_body');
    if (!upstreamOf('llm')) throw new HttpError(404, 'not_offered');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const templates = await listTemplates(owner);
    const template = resolveTemplate((body.template as string | undefined) ?? templateKeyOf(editable.manifest.summaryTemplate), templates);
    const source = summarySource(editable.manifest, validZone(typeof body.zone === 'string' ? body.zone : undefined));
    if (source.lines.length === 0) throw new HttpError(422, 'empty_transcript');

    const abort = new AbortController();
    req.signal.addEventListener('abort', () => abort.abort(), { once: true });
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      async start(controller) {
        const send = (event: Event) => {
          if (!abort.signal.aborted) controller.enqueue(encoder.encode(JSON.stringify(event) + '\n'));
        };
        let shown = '';
        let sentAt = 0;
        try {
          const reply = await summarise({
            ...source,
            maxChars: env().LLM_MAX_TRANSCRIPT_CHARS,
            body: template.body,
            chat: chatStream,
            signal: abort.signal,
            onStep: (step) => send({ step }),
            onText: (visible) => {
              shown = visible;
              if (Date.now() - sentAt < TEXT_EVERY_MS) return;
              sentAt = Date.now();
              send({ text: visible });
            },
          });
          if (abort.signal.aborted) return;
          send({ text: shown });
          const { prose, items } = splitSummary(reply, source.knownNames);
          if (!prose) {
            send({ error: 'empty_reply' });
            return;
          }
          applySummary(editable.manifest, { prose, items, template, now: Date.now() });
          const saved = await saveEdited(owner, syncId, editable);
          send({ done: true, revision: saved.revision });
        } catch (err) {
          if (abort.signal.aborted) return;
          if (err instanceof LlmError || err instanceof HttpError) {
            send({ error: err.code });
          } else {
            console.error('web summary failed', err);
            send({ error: 'internal' });
          }
        } finally {
          try {
            controller.close();
          } catch {
            // Already cancelled by the page.
          }
        }
      },
      cancel() {
        abort.abort();
      },
    });
    return new Response(stream, {
      headers: {
        'content-type': 'application/x-ndjson; charset=utf-8',
        // Next's gzip would hold the events back.
        'cache-control': 'no-store, no-transform',
        'x-accel-buffering': 'no',
      },
    });
  });
}
