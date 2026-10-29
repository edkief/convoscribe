import { listChanges } from '@/lib/documents';
import { authed, HttpError } from '@/lib/http';

const MAX_LIMIT = 500;

// Documents written after `since` (a `seq`), oldest first; page with `next` while `more`.
export async function GET(req: Request) {
  return authed(req, async (owner) => {
    const url = new URL(req.url);
    const since = Number(url.searchParams.get('since') ?? 0);
    const limit = Number(url.searchParams.get('limit') ?? 100);
    if (!Number.isSafeInteger(since) || since < 0) throw new HttpError(400, 'invalid_since');
    if (!Number.isInteger(limit) || limit < 1 || limit > MAX_LIMIT) throw new HttpError(400, 'invalid_limit');
    return Response.json(await listChanges(owner, since, limit));
  });
}
