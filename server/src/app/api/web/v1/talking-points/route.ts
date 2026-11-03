import { jsonBody, webAuthed } from '@/lib/http';
import { listTalkingPointLists } from '@/lib/talking-point-index';
import { createList, parseList } from '@/lib/talking-point-lists';

/** Your talking-point lists (#104), by name, read from Postgres: `{ lists: [{ syncId, revision, name, points, createdAt, changedAt }] }`. */
export async function GET(req: Request) {
  return webAuthed(req, async ({ owner }) => Response.json({ lists: await listTalkingPointLists(owner) }));
}

/**
 * Create a list (#104). The JSON body is `{ "name": "...", "points": ["...", ...] }`;
 * the answer's `revision` is the one to send with the next edit.
 */
export async function POST(req: Request) {
  return webAuthed(req, async ({ owner }) => {
    const meta = await createList(owner, parseList(await jsonBody(req, 64 * 1024)));
    return Response.json({ syncId: meta.syncId, revision: meta.revision }, { status: 201 });
  });
}
