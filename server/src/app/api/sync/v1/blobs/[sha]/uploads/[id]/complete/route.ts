import { completeUpload } from '@/lib/blobs';
import { authed, sha256Param } from '@/lib/http';

// Assemble the parts and verify the hash; 400 `hash_mismatch` discards them.
export async function POST(req: Request, ctx: RouteContext<'/api/sync/v1/blobs/[sha]/uploads/[id]/complete'>) {
  return authed(req, async (owner) => {
    const { sha, id } = await ctx.params;
    return Response.json(await completeUpload(owner, sha256Param(sha), id));
  });
}
