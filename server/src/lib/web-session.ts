import { getDocumentMeta, readManifest, type DocumentMeta } from './documents';
import { sessionView, type SessionView } from './transcript-export';

/**
 * A live session of `owner` with its current manifest read (#80), or null
 * when the owner has no such session or it was deleted. Another owner's
 * session is just as absent.
 */
export async function readSession(owner: string, syncId: string): Promise<{ meta: DocumentMeta; view: SessionView } | null> {
  const meta = await getDocumentMeta(owner, 'session', syncId);
  if (!meta || meta.deleted || !meta.manifestHash) return null;
  const bytes = await readManifest(owner, 'session', syncId, meta.manifestHash);
  let parsed: unknown = null;
  try {
    parsed = JSON.parse(Buffer.from(bytes).toString('utf8'));
  } catch {
    // Stored manifests were valid JSON when pushed; show an empty session rather than fail.
  }
  return { meta, view: sessionView(parsed) };
}
