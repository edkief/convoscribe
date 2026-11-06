import { upstreamOf } from '@/lib/ai-proxy';
import { answerQuestion, applyQa, QUESTION_MAX, questionSource } from '@/lib/ask';
import { env } from '@/lib/env';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream } from '@/lib/llm-client';
import { ndjsonResponse, throttled } from '@/lib/ndjson';
import { loadForEdit, saveEdited } from '@/lib/session-edit';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/questions'>;

/** How often the answer written so far is sent while it streams in. */
const TEXT_EVERY_MS = 100;

/** One line of the answer's stream. */
type Event = { text: string } | { done: true; revision: number } | { error: string };

/**
 * Ask the server's language model a question about a session (#118), as the
 * app does: the session's earlier questions and answers go with it, and a
 * transcript over `LLM_MAX_TRANSCRIPT_CHARS` is answered from its most
 * relevant parts. The JSON body is `{ "baseRevision": n, "question": "…" }`.
 * A request that can't start is a JSON error; once started, the answer is
 * NDJSON events: `text` with the answer so far, then `done` with the revision
 * that saved the question and answer, or `error`. Closing the request cancels
 * the model and saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const body = await jsonBody(req, 32 * 1024);
    const baseRevision = baseRevisionOf(body);
    const question = typeof body.question === 'string' ? body.question.trim().slice(0, QUESTION_MAX).trim() : '';
    if (!question) throw new HttpError(400, 'invalid_body');
    if (!upstreamOf('llm')) throw new HttpError(404, 'not_offered');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const source = questionSource(editable.manifest);
    if (source.lines.length === 0) throw new HttpError(422, 'empty_transcript');
    const askedAt = Date.now();

    return ndjsonResponse<Event>(req, 'web question', async (send, signal) => {
      const text = throttled((answer) => send({ text: answer }), TEXT_EVERY_MS);
      const { answer, excerpts } = await answerQuestion({
        ...source,
        question,
        maxChars: env().LLM_MAX_TRANSCRIPT_CHARS,
        chat: chatStream,
        signal,
        onText: text.text,
      });
      if (signal.aborted) return;
      text.flush();
      if (!answer.trim()) {
        send({ error: 'empty_reply' });
        return;
      }
      applyQa(editable.manifest, { question, answer, askedAt, now: Date.now(), excerpts });
      const saved = await saveEdited(owner, syncId, editable);
      send({ done: true, revision: saved.revision });
    });
  });
}
