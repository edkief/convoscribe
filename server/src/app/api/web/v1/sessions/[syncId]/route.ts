import { HttpError, readBody, syncIdParam, webAuthed } from '@/lib/http';
import { editSession, parseEdit } from '@/lib/session-edit';
import { deleteSessionFromWeb } from '@/lib/trash';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]'>;

/** A request's JSON body, or a 400 (or 413 over `limit` bytes). */
async function jsonBody(req: Request, limit: number): Promise<Record<string, unknown>> {
  let body: unknown;
  try {
    body = JSON.parse(Buffer.from(await readBody(req, limit)).toString('utf8'));
  } catch (err) {
    if (err instanceof HttpError) throw err;
    throw new HttpError(400, 'invalid_body');
  }
  return body && typeof body === 'object' ? (body as Record<string, unknown>) : {};
}

/** The body's `baseRevision`, the revision the page showed, or a 400. */
function baseRevisionOf(body: Record<string, unknown>): number {
  const { baseRevision } = body;
  if (typeof baseRevision !== 'number' || !Number.isSafeInteger(baseRevision) || baseRevision < 1) {
    throw new HttpError(400, 'invalid_body');
  }
  return baseRevision;
}

/**
 * Move a session to the trash (#81). The JSON body names the revision the
 * page showed, `{ "baseRevision": n }`; a session changed since is `409`,
 * for the page to reload, and never deleted unseen.
 */
export async function DELETE(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const meta = await deleteSessionFromWeb(owner, syncId, baseRevisionOf(await jsonBody(req, 1024)));
    return Response.json({ syncId, revision: meta.revision });
  });
}

/**
 * Edit a session (#84). The JSON body is `{ "baseRevision": n, "edit": {...} }`,
 * with the revision the page showed; a session changed since is `409`, for the
 * page to reload, and nothing is overwritten. The answer's `revision` is the
 * one to send next.
 */
export async function PATCH(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const body = await jsonBody(req, 64 * 1024);
    const meta = await editSession(owner, syncId, baseRevisionOf(body), parseEdit(body.edit));
    return Response.json({ syncId, revision: meta.revision });
  });
}
