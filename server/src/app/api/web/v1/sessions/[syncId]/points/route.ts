import { upstreamOf } from '@/lib/ai-proxy';
import { applyTicks, checkPoints, checkPointsByDecision, openPoints, promptLines, settledPoints } from '@/lib/assist';
import { decide, talkingPointsByDecision } from '@/lib/decision-client';
import { env } from '@/lib/env';
import { baseRevisionOf, HttpError, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { chatStream } from '@/lib/llm-client';
import { ndjsonResponse } from '@/lib/ndjson';
import { loadForEdit, saveEdited } from '@/lib/session-edit';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/points'>;

/** One line of the answer's stream. */
type Event = { step: { part: number; parts: number } } | { done: true; revision: number; ticked: number } | { error: string };

/**
 * *Check points* (#119): the server's language model reads the whole
 * transcript, in parts when it is over `LLM_MAX_TRANSCRIPT_CHARS`, and ticks
 * the talking points it finds covered, with where, as the app's
 * `TalkingPointChecker` does while recording, asking it for little reasoning
 * (#136). When the server has a decision service (#101) and
 * `DECISION_FOR_TALKING_POINTS` is on, that reads it instead, a line at a
 * time, in batches. Points ticked or unticked by hand are neither sent nor
 * changed. The JSON body is `{ "baseRevision": n }`.
 * A request that can't start is a JSON error; once started, the answer is
 * NDJSON: `step` before each part of a long transcript, then `done` with the
 * revision and how many points were ticked (no new revision for none), or
 * `error`. Closing the request saves nothing.
 */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const baseRevision = baseRevisionOf(await jsonBody(req, 4096));
    const byDecision = talkingPointsByDecision();
    if (!byDecision && !upstreamOf('llm')) throw new HttpError(404, 'not_offered');

    const editable = await loadForEdit(owner, syncId, baseRevision);
    const points = openPoints(editable.manifest);
    if (points.length === 0) throw new HttpError(422, 'no_open_points');
    const lines = promptLines(editable.manifest);
    if (lines.length === 0) throw new HttpError(422, 'empty_transcript');

    return ndjsonResponse<Event>(req, 'web talking points', async (send, signal) => {
      const onStep = (step: { part: number; parts: number }) => send({ step });
      const ticks = byDecision
        ? await checkPointsByDecision({ lines, points, others: settledPoints(editable.manifest), decide, signal, onStep })
        : await checkPoints({
            lines,
            points,
            maxChars: env().LLM_MAX_TRANSCRIPT_CHARS,
            chat: (messages, abort) => chatStream(messages, abort, { lowReasoning: true }),
            signal,
            onStep,
          });
      if (signal.aborted) return;
      if (!ticks) {
        send({ error: 'unreadable_reply' });
        return;
      }
      const ticked = applyTicks(editable.manifest, ticks);
      const saved = ticked ? await saveEdited(owner, syncId, editable) : editable.current;
      send({ done: true, revision: saved.revision, ticked });
    });
  });
}
