import { adminAuthed } from '@/lib/http';
import { serverStatus } from '@/lib/status';

/**
 * Whether Postgres and S3 can be reached, and what is stored (#82). Why one
 * cannot goes to the server's log only. `/api/health` stays free of
 * dependencies, for liveness probes.
 */
export async function GET(req: Request) {
  return adminAuthed(req, async () => Response.json(await serverStatus()));
}
