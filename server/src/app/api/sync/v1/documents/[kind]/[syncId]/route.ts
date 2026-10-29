import { gunzipSync } from 'node:zlib';
import { deleteDocument, getDocumentMeta, putDocument, readManifest, type DocumentMeta } from '@/lib/documents';
import { authed, HttpError, intHeader, kindParam, readBody, sha256Param, syncIdParam } from '@/lib/http';

type Ctx = RouteContext<'/api/sync/v1/documents/[kind]/[syncId]'>;

/** Largest manifest accepted, compressed on the wire and once inflated. */
const MAX_BODY_BYTES = 16 * 1024 * 1024;
const MAX_MANIFEST_BYTES = 128 * 1024 * 1024;

const params = async (ctx: Ctx) => {
  const { kind, syncId } = await ctx.params;
  return { kind: kindParam(kind), syncId: syncIdParam(syncId) };
};

const metaHeaders = (meta: DocumentMeta): Record<string, string> => ({
  'Sync-Revision': String(meta.revision),
  'Sync-Seq': String(meta.seq),
  'Sync-Duration-Ms': String(meta.durationMs),
  'Sync-Changed-At': String(meta.changedAt),
  ...(meta.manifestHash ? { 'Sync-Manifest-Hash': meta.manifestHash } : {}),
});

// The current manifest, with its revision in Sync-* headers; 410 for a deleted document.
export async function GET(req: Request, ctx: Ctx) {
  return authed(req, async (owner) => {
    const { kind, syncId } = await params(ctx);
    const meta = await getDocumentMeta(owner, kind, syncId);
    if (!meta) throw new HttpError(404, 'not_found');
    if (meta.deleted) return Response.json({ error: 'deleted', current: meta }, { status: 410 });
    const manifest = await readManifest(owner, kind, syncId, meta.manifestHash!);
    return new Response(Buffer.from(manifest), {
      headers: { 'Content-Type': 'application/json', ...metaHeaders(meta) },
    });
  });
}

/**
 * A new revision. The body is the manifest (optionally `Content-Encoding:
 * gzip`); headers carry Sync-Base-Revision (0 for a new document),
 * Sync-Manifest-Hash, Sync-Duration-Ms and Sync-Changed-At.
 */
export async function PUT(req: Request, ctx: Ctx) {
  return authed(req, async (owner) => {
    const { kind, syncId } = await params(ctx);
    const push = {
      baseRevision: intHeader(req, 'sync-base-revision'),
      durationMs: intHeader(req, 'sync-duration-ms'),
      changedAt: intHeader(req, 'sync-changed-at'),
      manifestHash: sha256Param(req.headers.get('sync-manifest-hash') ?? ''),
    };
    let manifest = await readBody(req, MAX_BODY_BYTES);
    if (req.headers.get('content-encoding')?.toLowerCase() === 'gzip') {
      try {
        manifest = gunzipSync(manifest, { maxOutputLength: MAX_MANIFEST_BYTES });
      } catch {
        throw new HttpError(400, 'invalid_gzip');
      }
    }
    const meta = await putDocument(owner, kind, syncId, { ...push, manifest });
    return Response.json(meta, { headers: metaHeaders(meta) });
  });
}

// Delete with Sync-Base-Revision; leaves a tombstone in the change feed.
export async function DELETE(req: Request, ctx: Ctx) {
  return authed(req, async (owner) => {
    const { kind, syncId } = await params(ctx);
    const meta = await deleteDocument(owner, kind, syncId, intHeader(req, 'sync-base-revision'));
    return Response.json(meta, { headers: metaHeaders(meta) });
  });
}
