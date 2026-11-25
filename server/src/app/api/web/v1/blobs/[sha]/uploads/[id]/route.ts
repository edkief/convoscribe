import { listParts } from '@/lib/blobs';
import { sha256Param, webAuthed } from '@/lib/http';

// The parts received so far, for the web app to resume an upload (#163).
export async function GET(req: Request, ctx: RouteContext<'/api/web/v1/blobs/[sha]/uploads/[id]'>) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const { sha, id } = await ctx.params;
    return Response.json({ parts: await listParts(owner, sha256Param(sha), id) });
  });
}
