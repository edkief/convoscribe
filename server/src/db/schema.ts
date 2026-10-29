import { sql } from 'drizzle-orm';
import {
  bigint,
  boolean,
  index,
  integer,
  pgSequence,
  pgTable,
  primaryKey,
  text,
  timestamp,
} from 'drizzle-orm/pg-core';

/** Orders every document write across all owners; the change feed reads it (#67). */
export const changeSeq = pgSequence('change_seq');

/**
 * One synced document per owner, kind and sync id (#68). The manifest itself
 * lives in the blob store; this row is the index the conflict rule and the
 * change feed work from. A delete keeps the row as a tombstone.
 */
export const documents = pgTable(
  'documents',
  {
    owner: text('owner').notNull(),
    kind: text('kind').notNull(),
    syncId: text('sync_id').notNull(),
    revision: integer('revision').notNull(),
    seq: bigint('seq', { mode: 'number' })
      .notNull()
      .default(sql`nextval('change_seq')`),
    durationMs: bigint('duration_ms', { mode: 'number' }).notNull().default(0),
    changedAt: bigint('changed_at', { mode: 'number' }).notNull(),
    manifestHash: text('manifest_hash'),
    deleted: boolean('deleted').notNull().default(false),
    updatedAt: timestamp('updated_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [primaryKey({ columns: [t.owner, t.kind, t.syncId] }), index('documents_owner_seq').on(t.owner, t.seq)],
);

/** The blobs a document's current revision refers to, for later clean-up. */
export const documentBlobs = pgTable(
  'document_blobs',
  {
    owner: text('owner').notNull(),
    kind: text('kind').notNull(),
    syncId: text('sync_id').notNull(),
    sha256: text('sha256').notNull(),
  },
  (t) => [primaryKey({ columns: [t.owner, t.kind, t.syncId, t.sha256] })],
);

/** Blobs whose bytes are stored and verified, by content hash. */
export const blobs = pgTable(
  'blobs',
  {
    owner: text('owner').notNull(),
    sha256: text('sha256').notNull(),
    size: bigint('size', { mode: 'number' }).notNull(),
    createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [primaryKey({ columns: [t.owner, t.sha256] })],
);

/** A blob upload in progress, resumable part by part. */
export const uploads = pgTable('uploads', {
  id: text('id').primaryKey(),
  owner: text('owner').notNull(),
  sha256: text('sha256').notNull(),
  storeUploadId: text('store_upload_id').notNull(),
  createdAt: timestamp('created_at', { withTimezone: true }).notNull().defaultNow(),
});

/** A part received for an upload, with the store's ETag for it. */
export const uploadParts = pgTable(
  'upload_parts',
  {
    uploadId: text('upload_id')
      .notNull()
      .references(() => uploads.id, { onDelete: 'cascade' }),
    n: integer('n').notNull(),
    size: bigint('size', { mode: 'number' }).notNull(),
    etag: text('etag').notNull(),
  },
  (t) => [primaryKey({ columns: [t.uploadId, t.n] })],
);
