import { completeUpload } from '@/lib/blobs';
import { sha256Param, webAuthed } from '@/lib/http';

// Assemble a web upload's parts and verify the hash (#163); 400 `hash_mismatch` discards them.
export async function POST(req: Request, ctx: RouteContext<'/api/web/v1/blobs/[sha]/uploads/[id]/complete'>) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const { sha, id } = await ctx.params;
    return Response.json(await completeUpload(owner, sha256Param(sha), id));
  });
}
