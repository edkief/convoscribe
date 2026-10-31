import { HttpError, syncIdParam, webAuthed } from '@/lib/http';
import { attachment, toMarkdown, toSrt, toVtt, type SessionView } from '@/lib/transcript-export';
import { readSession } from '@/lib/web-session';

type Ctx = RouteContext<'/api/web/v1/sessions/[syncId]/transcript/[format]'>;

const FORMATS: Record<string, { type: string; render: (view: SessionView) => string }> = {
  md: { type: 'text/markdown; charset=utf-8', render: toMarkdown },
  srt: { type: 'application/x-subrip; charset=utf-8', render: toSrt },
  vtt: { type: 'text/vtt; charset=utf-8', render: toVtt },
};

/** A session's transcript as a download (#80): `md`, `srt` or `vtt`. */
export async function GET(req: Request, ctx: Ctx) {
  return webAuthed(req, async ({ owner }) => {
    const { syncId, format } = await ctx.params;
    const kind = Object.hasOwn(FORMATS, format) ? FORMATS[format] : undefined;
    if (!kind) throw new HttpError(404, 'unknown_format');
    const session = await readSession(owner, syncIdParam(syncId));
    if (!session) throw new HttpError(404, 'not_found');
    return new Response(kind.render(session.view), {
      headers: { 'Content-Type': kind.type, 'Content-Disposition': attachment(session.view.title, format) },
    });
  });
}
