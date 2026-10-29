import { startUpload } from '@/lib/blobs';
import { authed, sha256Param } from '@/lib/http';

// Start uploading a blob; 200 `{exists:true}` when it is already stored.
export async function POST(req: Request, ctx: RouteContext<'/api/sync/v1/blobs/[sha]/uploads'>) {
  return authed(req, async (owner) => {
    const result = await startUpload(owner, sha256Param((await ctx.params).sha));
    return Response.json(result, { status: 'id' in result ? 201 : 200 });
  });
}
