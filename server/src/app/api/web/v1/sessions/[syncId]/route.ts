import { HttpError, readBody, syncIdParam, webAuthed } from '@/lib/http';
import { deleteSessionFromWeb } from '@/lib/trash';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]'>;

/**
 * Move a session to the trash (#81). The JSON body names the revision the
 * page showed, `{ "baseRevision": n }`; a session changed since is `409`,
 * for the page to reload, and never deleted unseen.
 */
export async function DELETE(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    let body: unknown;
    try {
      body = JSON.parse(Buffer.from(await readBody(req, 1024)).toString('utf8'));
    } catch (err) {
      if (err instanceof HttpError) throw err;
      throw new HttpError(400, 'invalid_body');
    }
    const baseRevision = (body as { baseRevision?: unknown } | null)?.baseRevision;
    if (typeof baseRevision !== 'number' || !Number.isSafeInteger(baseRevision) || baseRevision < 1) {
      throw new HttpError(400, 'invalid_body');
    }
    const meta = await deleteSessionFromWeb(owner, syncId, baseRevision);
    return Response.json({ syncId, revision: meta.revision });
  });
}
