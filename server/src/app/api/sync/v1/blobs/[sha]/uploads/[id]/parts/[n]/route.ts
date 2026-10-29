import { MAX_PART_BYTES, putPart } from '@/lib/blobs';
import { authed, readBody, sha256Param } from '@/lib/http';

// One part of an upload, at most 16 MB; sending it again replaces it.
export async function PUT(req: Request, ctx: RouteContext<'/api/sync/v1/blobs/[sha]/uploads/[id]/parts/[n]'>) {
  return authed(req, async (owner) => {
    const { sha, id, n } = await ctx.params;
    const bytes = await readBody(req, MAX_PART_BYTES);
    await putPart(owner, sha256Param(sha), id, Number(n), bytes);
    return new Response(null, { status: 204 });
  });
}
