import { MAX_PART_BYTES, putPart } from '@/lib/blobs';
import { readBody, sha256Param, webAuthed } from '@/lib/http';

// One part of a web upload (#163), at most 16 MB; sending it again replaces it.
export async function PUT(req: Request, ctx: RouteContext<'/api/web/v1/blobs/[sha]/uploads/[id]/parts/[n]'>) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const { sha, id, n } = await ctx.params;
    const bytes = await readBody(req, MAX_PART_BYTES);
    await putPart(owner, sha256Param(sha), id, Number(n), bytes);
    return new Response(null, { status: 204 });
  });
}
