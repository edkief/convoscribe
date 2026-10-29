import { resolveOwner, unauthorized } from '@/lib/auth';

// The owner the caller's key resolves to; lets a device check its settings.
export async function GET(req: Request) {
  const owner = await resolveOwner(req);
  if (!owner) return unauthorized();
  return Response.json({ owner });
}
