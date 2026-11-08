import { z } from 'zod';
import { ACCESS_SERVICES, setServices } from '@/lib/access';
import { adminAuthed, HttpError, readBody } from '@/lib/http';
import { listOwners, setQuota } from '@/lib/owners';

/** Everyone seen by this server, with counts, bytes and times (#82). Nothing from a manifest. */
export async function GET(req: Request) {
  return adminAuthed(req, async () => Response.json({ users: await listOwners() }));
}

const userBody = z
  .object({
    owner: z.string().min(1),
    quotaBytes: z.number().int().nonnegative().max(Number.MAX_SAFE_INTEGER).nullable().optional(),
    services: z.array(z.enum(ACCESS_SERVICES)).max(ACCESS_SERVICES.length * 2).nullable().optional(),
  })
  .refine((body) => body.quotaBytes !== undefined || body.services !== undefined);

/**
 * Set a user's storage quota (#82) and what they may use (#145): `{ "owner":
 * name, "quotaBytes": n, "services": [...] }`, either field left out to keep
 * it, `quotaBytes` null for unlimited, `services` null for
 * `DEFAULT_USER_SERVICES`. The name is in the body because a username can
 * hold anything; 404 for someone this server has not seen.
 */
export async function PUT(req: Request) {
  return adminAuthed(req, async () => {
    let parsed;
    try {
      parsed = userBody.safeParse(JSON.parse(Buffer.from(await readBody(req, 4096)).toString('utf8')));
    } catch (err) {
      if (err instanceof HttpError) throw err;
      throw new HttpError(400, 'invalid_body');
    }
    if (!parsed.success) throw new HttpError(400, 'invalid_body');
    const { owner, quotaBytes, services } = parsed.data;
    if (quotaBytes !== undefined) await setQuota(owner, quotaBytes);
    if (services !== undefined) await setServices(owner, services);
    return Response.json(parsed.data);
  });
}
