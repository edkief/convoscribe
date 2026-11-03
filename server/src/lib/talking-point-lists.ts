import { createHash, randomUUID } from 'node:crypto';
import { conflict, deleteDocument, getDocumentMeta, putDocument, readManifest, type DocumentMeta } from './documents';
import { HttpError } from './http';
import { cleanPoint, cleanPoints } from './talking-point-index';

/** The newest `TalkingPointListManifest` schema this server writes and edits (`LibraryManifests.SCHEMA` in the app). */
export const LIST_SCHEMA = 1;

/** Most points a list saved from the web may have. */
export const POINTS_MAX = 200;

/** A list as the web app sends it (#104), or a 400: a name that is not blank once cleaned, and its points in order. */
export function parseList(body: Record<string, unknown>): { name: string; points: string[] } {
  const { name, points } = body;
  if (typeof name !== 'string' || !Array.isArray(points) || !points.every((p) => typeof p === 'string')) {
    throw new HttpError(400, 'invalid_body');
  }
  const cleaned = cleanPoint(name);
  const kept = cleanPoints(points);
  if (!cleaned || kept.length > POINTS_MAX) throw new HttpError(400, 'invalid_body');
  return { name: cleaned, points: kept };
}

const bytesOf = (manifest: Record<string, unknown>) => {
  const bytes = Buffer.from(JSON.stringify(manifest));
  return { manifest: bytes, manifestHash: createHash('sha256').update(bytes).digest('hex') };
};

/**
 * Create a list from the web app (#104), as a device would: a new document
 * that devices download at their next sync.
 */
export async function createList(owner: string, list: { name: string; points: string[] }, now = Date.now()): Promise<DocumentMeta> {
  const syncId = randomUUID();
  const manifest = { schema: LIST_SCHEMA, syncId, name: list.name, points: list.points, createdAt: now };
  return putDocument(owner, 'talking-points', syncId, { ...bytesOf(manifest), baseRevision: 0, durationMs: 0, changedAt: now });
}

/**
 * Save a list's name and points from the web app (#104), from the revision
 * the page showed: 409 if it has changed since, 404 if it is gone, 422
 * `newer_schema` for one from a newer app. The stored manifest is patched,
 * so fields this server does not know survive. Saving what is already there
 * returns the current revision.
 */
export async function saveList(
  owner: string,
  syncId: string,
  baseRevision: number,
  list: { name: string; points: string[] },
): Promise<DocumentMeta> {
  const current = await getDocumentMeta(owner, 'talking-points', syncId);
  if (!current || current.deleted || !current.manifestHash) throw new HttpError(404, 'not_found');
  if (current.revision !== baseRevision) throw conflict(current);
  let manifest: unknown;
  try {
    manifest = JSON.parse(Buffer.from(await readManifest(owner, 'talking-points', syncId, current.manifestHash)).toString('utf8'));
  } catch (err) {
    if (err instanceof HttpError) throw err;
    throw new HttpError(422, 'invalid_manifest');
  }
  if (!manifest || typeof manifest !== 'object' || Array.isArray(manifest)) throw new HttpError(422, 'invalid_manifest');
  const m = manifest as Record<string, unknown>;
  if (m.schema !== undefined && (typeof m.schema !== 'number' || m.schema > LIST_SCHEMA)) throw new HttpError(422, 'newer_schema');
  if (m.name === list.name && JSON.stringify(m.points) === JSON.stringify(list.points)) return current;
  const next = { ...m, name: list.name, points: list.points };
  return putDocument(
    owner,
    'talking-points',
    syncId,
    {
      ...bytesOf(next),
      baseRevision,
      durationMs: current.durationMs,
      // Later than what it replaces even if a device's clock runs ahead: the most recent change wins.
      changedAt: Math.max(Date.now(), current.changedAt + 1),
    },
    { strict: true },
  );
}

/** Delete a list from the web app (#104), from the revision the page showed: 409 if it has changed since, 404 if it is gone. */
export function deleteList(owner: string, syncId: string, baseRevision: number): Promise<DocumentMeta> {
  return deleteDocument(owner, 'talking-points', syncId, baseRevision, null, { strict: true });
}
