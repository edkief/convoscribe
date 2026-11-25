import { jsonBody, webAuthed } from '@/lib/http';
import { createSession, parseNewSession } from '@/lib/session-create';

/** Largest create request: an hour's transcript with word timings is a few MB. */
const BODY_LIMIT = 16 * 1024 * 1024;

/**
 * Create a session recorded in the web app (#163), once its recording is
 * uploaded through `/api/web/v1/blobs`. The JSON body is `{ syncId,
 * audioSha, durationMs, createdAt, language, lines: [{ startMs, endMs,
 * text, tokens? }], title?, zone?, asrModel? }`; the server writes the
 * manifest. `201 { syncId, revision }`, or `200` with the same revision for
 * a request sent again; `409` for another session under that `syncId`.
 */
export async function POST(req: Request) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const { meta, created } = await createSession(owner, parseNewSession(await jsonBody(req, BODY_LIMIT)));
    return Response.json({ syncId: meta.syncId, revision: meta.revision }, { status: created ? 201 : 200 });
  });
}
