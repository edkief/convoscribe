import { cleanupStatus, startManualCleanup } from '@/lib/cleanup';
import { adminAuthed, HttpError, readBody } from '@/lib/http';

/** Clean-up's daily mode, its last run and preview, and whether either is going (#82). */
export async function GET(req: Request) {
  return adminAuthed(req, async () => Response.json(await cleanupStatus()));
}

/**
 * Start a clean-up in the background (#82): `{ "dryRun": true }` for a
 * preview, which removes nothing. `202` with the run's id, `409
 * cleanup_running` when one of the same kind is still going.
 */
export async function POST(req: Request) {
  return adminAuthed(req, async () => {
    let body: unknown;
    try {
      body = JSON.parse(Buffer.from(await readBody(req, 1024)).toString('utf8'));
    } catch (err) {
      if (err instanceof HttpError) throw err;
      throw new HttpError(400, 'invalid_body');
    }
    const dryRun = (body as { dryRun?: unknown } | null)?.dryRun;
    if (typeof dryRun !== 'boolean') throw new HttpError(400, 'invalid_body');
    return Response.json(await startManualCleanup(dryRun), { status: 202 });
  });
}
