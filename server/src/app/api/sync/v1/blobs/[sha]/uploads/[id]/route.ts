import { listParts } from '@/lib/blobs';
import { authed, sha256Param } from '@/lib/http';

// The parts received so far, for resuming.
export async function GET(req: Request, ctx: RouteContext<'/api/sync/v1/blobs/[sha]/uploads/[id]'>) {
  return authed(req, async (owner) => {
    const { sha, id } = await ctx.params;
    return Response.json({ parts: await listParts(owner, sha256Param(sha), id) });
  });
}
