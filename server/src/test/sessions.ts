import { createHash } from 'node:crypto';
import { blobs } from '@/db/schema';
import { blobKeys, blobStore } from '@/lib/blob-store';
import { db } from '@/lib/db';
import { putDocument } from '@/lib/documents';
import type { Kind } from '@/lib/http';

const sha256 = (bytes: Uint8Array | string) => createHash('sha256').update(bytes).digest('hex');

/** A session manifest as the app writes it, with `over` on top. */
export function sessionManifest(syncId: string, over: Record<string, unknown> = {}) {
  return {
    schema: 1,
    syncId,
    title: `Session ${syncId}`,
    titleSource: 'user',
    createdAt: 1_760_000_000_000,
    durationMs: 65_000,
    language: 'en',
    status: 'DIARIZED',
    lines: [
      { startMs: 0, endMs: 4000, text: 'Hello there.', speakerIndex: 0, tokens: [] },
      { startMs: 4000, endMs: 9000, text: 'Hi, how are you?', speakerIndex: 1, tokens: [] },
    ],
    speakers: [
      { index: 0, label: 'Alice' },
      { index: 1, label: 'Bob' },
    ],
    messages: [],
    marks: [],
    tags: [],
    ...over,
  };
}

/** Store `bytes` as a blob of `owner`, as a completed upload would. */
export async function storeBlob(owner: string, bytes: Uint8Array | string) {
  const data = typeof bytes === 'string' ? Buffer.from(bytes) : bytes;
  const sha = sha256(data);
  await blobStore().put(blobKeys.blob(owner, sha), data);
  await db().insert(blobs).values({ owner, sha256: sha, size: data.length }).onConflictDoNothing();
  return { sha256: sha, size: data.length };
}

/** Push `manifest` as a new revision of a document from `baseRevision`. */
export function pushManifest(owner: string, manifest: unknown, baseRevision = 0, kind: Kind = 'session', syncId?: string) {
  const bytes = Buffer.from(JSON.stringify(manifest));
  const id = syncId ?? (manifest as { syncId: string }).syncId;
  return putDocument(owner, kind, id, {
    manifest: bytes,
    manifestHash: sha256(bytes),
    baseRevision,
    durationMs: (manifest as { durationMs?: number }).durationMs ?? 0,
    changedAt: 1_760_000_100_000,
  });
}
