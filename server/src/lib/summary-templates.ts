import { and, eq, isNotNull } from 'drizzle-orm';
import { documents } from '@/db/schema';
import { db } from './db';
import { readManifest } from './documents';
import { BUILT_IN_TEMPLATES, MEETING, type SummaryTemplate } from './summary-prompt';

/** The key the web app names a user template by: its name, which is how a session refers to it. */
const USER_PREFIX = 'user:';

/**
 * The built-in templates and `owner`'s own (#117), by name after the
 * built-ins. A user template is a synced `template` document (#70); one
 * whose manifest can't be read is left out.
 */
export async function listTemplates(owner: string): Promise<SummaryTemplate[]> {
  const rows = await db()
    .select({ syncId: documents.syncId, manifestHash: documents.manifestHash })
    .from(documents)
    .where(and(eq(documents.owner, owner), eq(documents.kind, 'template'), eq(documents.deleted, false), isNotNull(documents.manifestHash)));
  const mine = await Promise.all(
    rows.map(async ({ syncId, manifestHash }): Promise<SummaryTemplate | null> => {
      try {
        const m = JSON.parse(Buffer.from(await readManifest(owner, 'template', syncId, manifestHash!)).toString('utf8')) as Record<string, unknown>;
        const name = typeof m.name === 'string' ? m.name.trim() : '';
        if (!name || typeof m.body !== 'string') return null;
        return { key: USER_PREFIX + name, name, body: m.body, builtIn: false };
      } catch {
        return null;
      }
    }),
  );
  const byName = new Map<string, SummaryTemplate>();
  for (const t of mine) if (t && !byName.has(t.key)) byName.set(t.key, t);
  return [...BUILT_IN_TEMPLATES, ...[...byName.values()].sort((a, b) => a.name.localeCompare(b.name))];
}

/** The manifest's reference to `template`, as the app's `SessionManifests.templateRef` writes it. */
export const templateRef = (template: SummaryTemplate) => (template.builtIn ? { key: template.key } : { user: template.name });

/** The template `key` names among `templates`, or Meeting when it is unknown or deleted, as the app resolves it. */
export const resolveTemplate = (key: string | null, templates: SummaryTemplate[]) => templates.find((t) => t.key === key) ?? MEETING;
