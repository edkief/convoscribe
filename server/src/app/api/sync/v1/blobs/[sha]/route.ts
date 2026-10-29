import { blobKeys, blobStore } from '@/lib/blob-store';
import { blobSize } from '@/lib/blobs';
import { authed, HttpError, parseRange, sha256Param } from '@/lib/http';

type Ctx = RouteContext<'/api/sync/v1/blobs/[sha]'>;

// Whether a blob is stored, so a device never sends it twice.
export async function HEAD(req: Request, ctx: Ctx) {
  return authed(req, async (owner) => {
    const size = await blobSize(owner, sha256Param((await ctx.params).sha));
    if (size === null) return new Response(null, { status: 404 });
    return new Response(null, { status: 200, headers: { 'Content-Length': String(size), 'Accept-Ranges': 'bytes' } });
  });
}

// A blob's bytes; `Range` lets an interrupted download resume.
export async function GET(req: Request, ctx: Ctx) {
  return authed(req, async (owner) => {
    const sha = sha256Param((await ctx.params).sha);
    const size = await blobSize(owner, sha);
    if (size === null) throw new HttpError(404, 'not_found');
    const range = parseRange(req.headers.get('range'), size);
    const object = await blobStore().get(blobKeys.blob(owner, sha), range);
    if (!object) throw new HttpError(404, 'not_found');
    const headers: Record<string, string> = {
      'Content-Type': 'application/octet-stream',
      'Accept-Ranges': 'bytes',
      ETag: `"${sha}"`,
    };
    if (range) {
      headers['Content-Range'] = `bytes ${range.start}-${range.end}/${size}`;
      headers['Content-Length'] = String(range.end - range.start + 1);
      return new Response(object.body, { status: 206, headers });
    }
    headers['Content-Length'] = String(size);
    return new Response(object.body, { status: 200, headers });
  });
}
