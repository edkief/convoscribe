import {
  AbortMultipartUploadCommand,
  CompleteMultipartUploadCommand,
  CopyObjectCommand,
  CreateMultipartUploadCommand,
  DeleteObjectCommand,
  GetObjectCommand,
  HeadObjectCommand,
  NoSuchKey,
  NotFound,
  PutObjectCommand,
  UploadPartCommand,
} from '@aws-sdk/client-s3';
import { randomUUID } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { mkdir, readdir, readFile, rm, stat, writeFile, appendFile, rename } from 'node:fs/promises';
import path from 'node:path';
import { Readable } from 'node:stream';
import { env } from './env';
import { s3 } from './s3';

/** An inclusive byte range, as in an HTTP `Range` header. */
export type ByteRange = { start: number; end: number };

/** An object's bytes, or the requested range of them. */
export type StoredObject = { body: ReadableStream<Uint8Array>; size: number; range?: ByteRange };

/**
 * Where synced bytes live (#68): manifests and recordings, by key. Multipart
 * uploads let a device send a large recording part by part and resume.
 */
export interface BlobStore {
  put(key: string, bytes: Uint8Array): Promise<void>;
  /** The object, or null when it does not exist. */
  get(key: string, range?: ByteRange): Promise<StoredObject | null>;
  /** The object's size, or null when it does not exist. */
  head(key: string): Promise<number | null>;
  delete(key: string): Promise<void>;
  /** Move an object to `to`, replacing what is there (at most 5 GB on S3). */
  move(from: string, to: string): Promise<void>;
  createMultipart(key: string): Promise<string>;
  /** Stores part `n` (from 1) and returns its ETag. */
  uploadPart(key: string, uploadId: string, n: number, bytes: Uint8Array): Promise<string>;
  completeMultipart(key: string, uploadId: string, parts: { n: number; etag: string }[]): Promise<void>;
  abortMultipart(key: string, uploadId: string): Promise<void>;
}

/** Keys are scoped by owner from the first version, so multi-user needs no migration. */
export const blobKeys = {
  blob: (owner: string, sha256: string) => `users/${encodeURIComponent(owner)}/blobs/${sha256}`,
  /** Where an upload is assembled until its hash is checked. */
  staging: (owner: string, uploadId: string) => `users/${encodeURIComponent(owner)}/uploads/${uploadId}`,
  manifest: (owner: string, kind: string, syncId: string, manifestHash: string) =>
    `users/${encodeURIComponent(owner)}/docs/${kind}/${syncId}/${manifestHash}.json.gz`,
};

const rangeHeader = (range?: ByteRange) => (range ? `bytes=${range.start}-${range.end}` : undefined);

export class S3BlobStore implements BlobStore {
  constructor(private readonly bucket: string = env().S3_BUCKET) {}

  async put(key: string, bytes: Uint8Array) {
    await s3().send(new PutObjectCommand({ Bucket: this.bucket, Key: key, Body: bytes }));
  }

  async get(key: string, range?: ByteRange): Promise<StoredObject | null> {
    try {
      const res = await s3().send(new GetObjectCommand({ Bucket: this.bucket, Key: key, Range: rangeHeader(range) }));
      const total = range ? Number(res.ContentRange?.split('/')[1]) : Number(res.ContentLength);
      return { body: res.Body!.transformToWebStream() as ReadableStream<Uint8Array>, size: total, range };
    } catch (err) {
      if (err instanceof NoSuchKey) return null;
      throw err;
    }
  }

  async head(key: string) {
    try {
      const res = await s3().send(new HeadObjectCommand({ Bucket: this.bucket, Key: key }));
      return Number(res.ContentLength);
    } catch (err) {
      if (err instanceof NotFound || (err as { name?: string }).name === 'NotFound') return null;
      throw err;
    }
  }

  async delete(key: string) {
    await s3().send(new DeleteObjectCommand({ Bucket: this.bucket, Key: key }));
  }

  async move(from: string, to: string) {
    await s3().send(
      new CopyObjectCommand({ Bucket: this.bucket, Key: to, CopySource: `${this.bucket}/${encodeURI(from)}` }),
    );
    await this.delete(from);
  }

  async createMultipart(key: string) {
    const res = await s3().send(new CreateMultipartUploadCommand({ Bucket: this.bucket, Key: key }));
    return res.UploadId!;
  }

  async uploadPart(key: string, uploadId: string, n: number, bytes: Uint8Array) {
    const res = await s3().send(
      new UploadPartCommand({ Bucket: this.bucket, Key: key, UploadId: uploadId, PartNumber: n, Body: bytes }),
    );
    return res.ETag!;
  }

  async completeMultipart(key: string, uploadId: string, parts: { n: number; etag: string }[]) {
    await s3().send(
      new CompleteMultipartUploadCommand({
        Bucket: this.bucket,
        Key: key,
        UploadId: uploadId,
        MultipartUpload: { Parts: parts.map((p) => ({ PartNumber: p.n, ETag: p.etag })) },
      }),
    );
  }

  async abortMultipart(key: string, uploadId: string) {
    await s3().send(new AbortMultipartUploadCommand({ Bucket: this.bucket, Key: key, UploadId: uploadId }));
  }
}

/** Objects as files under `root`; for tests and single-machine setups. */
export class FsBlobStore implements BlobStore {
  constructor(private readonly root: string) {}

  private file(key: string) {
    return path.join(this.root, 'objects', key);
  }

  private uploadDir(uploadId: string) {
    return path.join(this.root, 'uploads', uploadId);
  }

  async put(key: string, bytes: Uint8Array) {
    const file = this.file(key);
    await mkdir(path.dirname(file), { recursive: true });
    await writeFile(file, bytes);
  }

  async get(key: string, range?: ByteRange): Promise<StoredObject | null> {
    const size = await this.head(key);
    if (size === null) return null;
    const stream = createReadStream(this.file(key), range ? { start: range.start, end: range.end } : {});
    return { body: Readable.toWeb(stream) as ReadableStream<Uint8Array>, size, range };
  }

  async head(key: string) {
    try {
      return (await stat(this.file(key))).size;
    } catch {
      return null;
    }
  }

  async delete(key: string) {
    await rm(this.file(key), { force: true });
  }

  async move(from: string, to: string) {
    await mkdir(path.dirname(this.file(to)), { recursive: true });
    await rename(this.file(from), this.file(to));
  }

  async createMultipart() {
    const id = randomUUID();
    await mkdir(this.uploadDir(id), { recursive: true });
    return id;
  }

  async uploadPart(_key: string, uploadId: string, n: number, bytes: Uint8Array) {
    await writeFile(path.join(this.uploadDir(uploadId), String(n)), bytes);
    return `"${n}-${bytes.length}"`;
  }

  async completeMultipart(key: string, uploadId: string, parts: { n: number; etag: string }[]) {
    const dir = this.uploadDir(uploadId);
    const present = new Set(await readdir(dir));
    const file = this.file(key);
    await mkdir(path.dirname(file), { recursive: true });
    const tmp = `${file}.${uploadId}.tmp`;
    await writeFile(tmp, new Uint8Array());
    for (const part of [...parts].sort((a, b) => a.n - b.n)) {
      if (!present.has(String(part.n))) throw new Error(`missing part ${part.n}`);
      await appendFile(tmp, await readFile(path.join(dir, String(part.n))));
    }
    await rename(tmp, file);
    await rm(dir, { recursive: true, force: true });
  }

  async abortMultipart(_key: string, uploadId: string) {
    await rm(this.uploadDir(uploadId), { recursive: true, force: true });
  }
}

let store: BlobStore | undefined;

/** The configured blob store (S3). */
export function blobStore(): BlobStore {
  store ??= new S3BlobStore();
  return store;
}

/** Use `value` as the blob store (tests only); `undefined` goes back to S3. */
export function setBlobStore(value: BlobStore | undefined) {
  store = value;
}
