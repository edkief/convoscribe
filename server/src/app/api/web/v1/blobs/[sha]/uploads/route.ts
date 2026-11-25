import { startUpload } from '@/lib/blobs';
import { sha256Param, webAuthed } from '@/lib/http';

/**
 * Start uploading a recording from the web app (#163), as a device does
 * through the sync API: `201 {id}`, or `200 {exists:true}` when it is
 * already stored. `507` when the user has no room left.
 */
export async function POST(req: Request, ctx: RouteContext<'/api/web/v1/blobs/[sha]/uploads'>) {
  return webAuthed(req, 'sync', async ({ owner }) => {
    const result = await startUpload(owner, sha256Param((await ctx.params).sha));
    return Response.json(result, { status: 'id' in result ? 201 : 200 });
  });
}
