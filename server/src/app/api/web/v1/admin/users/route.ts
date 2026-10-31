import { z } from 'zod';
import { adminAuthed, HttpError, readBody } from '@/lib/http';
import { listOwners, setQuota } from '@/lib/owners';

/** Everyone seen by this server, with counts, bytes and times (#82). Nothing from a manifest. */
export async function GET(req: Request) {
  return adminAuthed(req, async () => Response.json({ users: await listOwners() }));
}

const quotaBody = z.object({
  owner: z.string().min(1),
  quotaBytes: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).nullable(),
});

/**
 * Set a user's storage quota (#82): `{ "owner": name, "quotaBytes": n }`,
 * null for unlimited. The name is in the body because a username can hold
 * anything; 404 for someone this server has not seen.
 */
export async function PUT(req: Request) {
  return adminAuthed(req, async () => {
    let parsed;
    try {
      parsed = quotaBody.safeParse(JSON.parse(Buffer.from(await readBody(req, 4096)).toString('utf8')));
    } catch (err) {
      if (err instanceof HttpError) throw err;
      throw new HttpError(400, 'invalid_body');
    }
    if (!parsed.success) throw new HttpError(400, 'invalid_body');
    await setQuota(parsed.data.owner, parsed.data.quotaBytes);
    return Response.json(parsed.data);
  });
}
