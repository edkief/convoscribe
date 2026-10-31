import { purgeTrash } from '@/lib/documents';
import { webAuthed } from '@/lib/http';

/**
 * Empty the trash (#81): every session in it is removed for good, and
 * clean-up frees its storage on its next run.
 */
export async function DELETE(req: Request) {
  return webAuthed(req, async ({ owner }) => Response.json({ purged: await purgeTrash({ owner }) }));
}
