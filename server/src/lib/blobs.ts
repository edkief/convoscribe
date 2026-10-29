import { and, asc, eq } from 'drizzle-orm';
import { createHash, randomUUID } from 'node:crypto';
import { blobs, uploadParts, uploads } from '@/db/schema';
import { blobKeys, blobStore } from './blob-store';
import { db } from './db';
import { HttpError } from './http';

/** Largest part accepted; devices send about 8 MB (#67). */
export const MAX_PART_BYTES = 16 * 1024 * 1024;

/** The size of a stored blob, or null when the owner has none with this hash. */
export async function blobSize(owner: string, sha256: string): Promise<number | null> {
  const [row] = await db()
    .select({ size: blobs.size })
    .from(blobs)
    .where(and(eq(blobs.owner, owner), eq(blobs.sha256, sha256)));
  return row?.size ?? null;
}

/** Which of `shas` the owner has not stored yet. */
export async function missingBlobs(owner: string, shas: string[]): Promise<string[]> {
  const missing: string[] = [];
  for (const sha of new Set(shas)) if ((await blobSize(owner, sha)) === null) missing.push(sha);
  return missing;
}

/** Start an upload of `sha256`; a blob already stored needs none. */
export async function startUpload(owner: string, sha256: string): Promise<{ exists: true } | { id: string }> {
  if ((await blobSize(owner, sha256)) !== null) return { exists: true };
  const id = randomUUID();
  const storeUploadId = await blobStore().createMultipart(blobKeys.staging(owner, id));
  await db().insert(uploads).values({ id, owner, sha256, storeUploadId });
  return { id };
}

async function upload(owner: string, sha256: string, id: string) {
  const [row] = await db()
    .select()
    .from(uploads)
    .where(and(eq(uploads.id, id), eq(uploads.owner, owner), eq(uploads.sha256, sha256)));
  if (!row) throw new HttpError(404, 'unknown_upload');
  return row;
}

/** The parts received so far, so an interrupted device sends only the rest. */
export async function listParts(owner: string, sha256: string, id: string) {
  await upload(owner, sha256, id);
  return db()
    .select({ n: uploadParts.n, size: uploadParts.size })
    .from(uploadParts)
    .where(eq(uploadParts.uploadId, id))
    .orderBy(asc(uploadParts.n));
}

/** Store part `n` (1 to 10000); sending a part again replaces it. */
export async function putPart(owner: string, sha256: string, id: string, n: number, bytes: Uint8Array) {
  if (!Number.isInteger(n) || n < 1 || n > 10000) throw new HttpError(400, 'invalid_part');
  if (bytes.length === 0) throw new HttpError(400, 'empty_part');
  const row = await upload(owner, sha256, id);
  const etag = await blobStore().uploadPart(blobKeys.staging(owner, id), row.storeUploadId, n, bytes);
  await db()
    .insert(uploadParts)
    .values({ uploadId: id, n, size: bytes.length, etag })
    .onConflictDoUpdate({ target: [uploadParts.uploadId, uploadParts.n], set: { size: bytes.length, etag } });
}

async function sha256Of(stream: ReadableStream<Uint8Array>): Promise<string> {
  const hash = createHash('sha256');
  for await (const chunk of stream as unknown as AsyncIterable<Uint8Array>) hash.update(chunk);
  return hash.digest('hex');
}

/**
 * Assemble the parts and check the result hashes to `sha256`. A mismatch
 * deletes it and answers 400, so a stored blob is always what its name says.
 */
export async function completeUpload(owner: string, sha256: string, id: string): Promise<{ size: number }> {
  const row = await upload(owner, sha256, id);
  const parts = await db()
    .select()
    .from(uploadParts)
    .where(eq(uploadParts.uploadId, id))
    .orderBy(asc(uploadParts.n));
  if (parts.length === 0 || parts.some((p, i) => p.n !== i + 1)) {
    throw new HttpError(409, 'parts_missing', { received: parts.map((p) => p.n) });
  }
  const staging = blobKeys.staging(owner, id);
  const store = blobStore();
  await store.completeMultipart(
    staging,
    row.storeUploadId,
    parts.map((p) => ({ n: p.n, etag: p.etag })),
  );
  await db().delete(uploads).where(eq(uploads.id, id));
  const object = await store.get(staging);
  const actual = object ? await sha256Of(object.body) : null;
  if (actual !== sha256) {
    await store.delete(staging);
    throw new HttpError(400, 'hash_mismatch', { actual });
  }
  const size = object!.size;
  await store.move(staging, blobKeys.blob(owner, sha256));
  await db().insert(blobs).values({ owner, sha256, size }).onConflictDoNothing();
  return { size };
}
