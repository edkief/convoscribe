import { adminAuthed, HttpError } from '@/lib/http';
import { isStatusCheck, runCheck } from '@/lib/status';

/**
 * One dependency's reachability, so the status page can show each as soon as
 * it answers rather than waiting on the slowest (#82, #145). `{ state }`,
 * null when the server is not set up with it; 404 for a name it doesn't know.
 */
export async function GET(req: Request, ctx: RouteContext<'/api/web/v1/admin/status/[check]'>) {
  return adminAuthed(req, async () => {
    const { check } = await ctx.params;
    if (!isStatusCheck(check)) throw new HttpError(404, 'not_found');
    return Response.json({ state: await runCheck(check) }, { headers: { 'cache-control': 'no-store' } });
  });
}
