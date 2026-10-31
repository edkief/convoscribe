import { blobKeys, blobStore } from '@/lib/blob-store';
import { blobSize } from '@/lib/blobs';
import { getDocumentMeta } from '@/lib/documents';
import { HttpError, parseRange, syncIdParam, webAuthed } from '@/lib/http';
import { getSessionIndex } from '@/lib/session-index';
import { attachment } from '@/lib/transcript-export';
import { readSession } from '@/lib/web-session';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/audio'>;

/** The recording `?clean=1` asks for, and the session's title, from the index while it is current. */
async function recording(owner: string, syncId: string, clean: boolean) {
  const meta = await getDocumentMeta(owner, 'session', syncId);
  if (!meta || meta.deleted) return null;
  const row = await getSessionIndex(owner, syncId);
  if (row && row.manifestHash === meta.manifestHash) {
    return { sha256: clean ? row.cleanAudioSha256 : row.audioSha256, title: row.title ?? 'session' };
  }
  const session = await readSession(owner, syncId);
  if (!session) return null;
  return { sha256: (clean ? session.view.cleanAudio : session.view.audio)?.sha256 ?? null, title: session.view.title };
}

/**
 * A session's recording for the web player (#80), streamed from the blob
 * store, never held in memory; `Range` gives `206` so the player seeks.
 * `?clean=1` is the cleaned recording, `?download=1` makes it a download.
 */
export async function GET(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const syncId = syncIdParam((await ctx.params).syncId);
    const query = new URL(req.url).searchParams;
    const clean = query.get('clean') === '1';
    const found = await recording(owner, syncId, clean);
    if (!found?.sha256) throw new HttpError(404, 'not_found');
    const size = await blobSize(owner, found.sha256);
    if (size === null) throw new HttpError(404, 'not_found');
    const range = parseRange(req.headers.get('range'), size);
    const object = await blobStore().get(blobKeys.blob(owner, found.sha256), range);
    if (!object) throw new HttpError(404, 'not_found');
    const headers: Record<string, string> = {
      'Content-Type': 'audio/wav',
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'private, no-cache',
      ETag: `"${found.sha256}"`,
    };
    if (query.get('download') === '1') {
      headers['Content-Disposition'] = attachment(clean ? `${found.title} (cleaned)` : found.title, 'wav');
    }
    if (range) {
      headers['Content-Range'] = `bytes ${range.start}-${range.end}/${size}`;
      headers['Content-Length'] = String(range.end - range.start + 1);
      return new Response(object.body, { status: 206, headers });
    }
    headers['Content-Length'] = String(size);
    return new Response(object.body, { status: 200, headers });
  });
}
