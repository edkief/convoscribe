import { baseRevisionOf, jsonBody, syncIdParam, webAuthed } from '@/lib/http';
import { deleteList, parseList, saveList } from '@/lib/talking-point-lists';

type Ctx = RouteContext<'/api/web/v1/talking-points/[syncId]'>;

/**
 * Save a list's name and points (#104). The JSON body is
 * `{ "baseRevision": n, "name": "...", "points": [...] }`, with the revision
 * the page showed; a list changed since is `409`, for the page to reload, and
 * nothing is overwritten. The answer's `revision` is the one to send next.
 */
export async function PUT(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const body = await jsonBody(req, 64 * 1024);
    const meta = await saveList(owner, syncId, baseRevisionOf(body), parseList(body));
    return Response.json({ syncId, revision: meta.revision });
  });
}

/** Delete a list (#104), from `{ "baseRevision": n }`; one changed since is `409`. */
export async function DELETE(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const meta = await deleteList(owner, syncId, baseRevisionOf(await jsonBody(req, 1024)));
    return Response.json({ syncId, revision: meta.revision });
  });
}
