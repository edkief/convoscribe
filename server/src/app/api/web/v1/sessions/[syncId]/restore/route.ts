import { restoreSession } from '@/lib/documents';
import { syncIdParam, webAuthed } from '@/lib/http';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/restore'>;

/** Bring a session back from the trash (#81); `404 not_in_trash` when it can't be. */
export async function POST(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const meta = await restoreSession(owner, syncId);
    return Response.json({ syncId, revision: meta.revision });
  });
}
