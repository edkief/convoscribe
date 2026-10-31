import { sql } from 'drizzle-orm';
import {
  bigint,
  boolean,
  index,
  integer,
  jsonb,
  pgSequence,
  pgTable,
  primaryKey,
  serial,
  text,
  timestamp,
} from 'drizzle-orm/pg-core';

/** Orders every document write across all owners; the change feed reads it (#67). */
export const changeSeq = pgSequence('change_seq');

/**
 * One synced document per owner, kind and sync id (#68). The manifest itself
 * lives in the blob store; this row is the index the conflict rule and the
 * change feed work from. A delete keeps the row as a tombstone; a deleted
 * session's tombstone also keeps its manifest and recordings in the trash
 * for 30 days (#81), so the web app can restore it.
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
    /** When a deleted session went to the trash; null when live, purged, or deleted before #81. */
    trashedAt: timestamp('trashed_at', { withTimezone: true }),
    /** The manifest a restore brings back, while the session is in the trash. */
    trashedManifestHash: text('trashed_manifest_hash'),
  },
  (t) => [primaryKey({ columns: [t.owner, t.kind, t.syncId] }), index('documents_owner_seq').on(t.owner, t.seq)],
);

/**
 * The blobs a document's current revision refers to, or a trashed
 * session's; clean-up removes any blob no row refers to (#81).
 */
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
    /**
     * When the blob was last stored or confirmed to a device as stored;
     * clean-up's grace period counts from it, so a push about to refer to
     * the blob finds it still there (#81).
     */
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

/**
 * What the web app lists and filters sessions by (#79, decision 3), copied
 * from each session's current manifest when it is pushed, so the library
 * never reads S3. The manifests stay the source of truth: a row can always
 * be rebuilt from them (see `backfillSessionIndex`). Any field the manifest
 * lacks or has in an unknown shape is null. A delete leaves the row; the
 * library joins `documents` to leave deleted sessions out.
 */
export const sessionIndex = pgTable(
  'session_index',
  {
    owner: text('owner').notNull(),
    syncId: text('sync_id').notNull(),
    /** The manifest this row was read from. */
    manifestHash: text('manifest_hash').notNull(),
    title: text('title'),
    /** When the session was recorded (ms since epoch); the push's `changedAt` when unknown. */
    createdAt: bigint('created_at', { mode: 'number' }).notNull(),
    durationMs: bigint('duration_ms', { mode: 'number' }),
    language: text('language'),
    status: text('status'),
    tags: text('tags').array().notNull().default(sql`'{}'::text[]`),
    speakerCount: integer('speaker_count'),
    /** Speaker labels, in speaker order. */
    speakers: text('speakers').array().notNull().default(sql`'{}'::text[]`),
    /** A line to recognise the session by: the start of its latest summary, else of its transcript. */
    preview: text('preview'),
    /** Action items neither done nor deleted. */
    openActions: integer('open_actions').notNull().default(0),
    lineCount: integer('line_count'),
    audioSha256: text('audio_sha256'),
    audioSize: bigint('audio_size', { mode: 'number' }),
    cleanAudioSha256: text('clean_audio_sha256'),
    cleanAudioSize: bigint('clean_audio_size', { mode: 'number' }),
    indexedAt: timestamp('indexed_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (t) => [
    primaryKey({ columns: [t.owner, t.syncId] }),
    index('session_index_owner_created').on(t.owner, t.createdAt.desc(), t.syncId.desc()),
    index('session_index_tags').using('gin', t.tags),
  ],
);

/** One run of storage clean-up (#81), with what it removed or, dry, would remove. */
export const cleanupRuns = pgTable('cleanup_runs', {
  id: serial('id').primaryKey(),
  /** `scheduled` (the daily run) or `manual`. */
  trigger: text('trigger').notNull(),
  dryRun: boolean('dry_run').notNull(),
  startedAt: timestamp('started_at', { withTimezone: true }).notNull().defaultNow(),
  finishedAt: timestamp('finished_at', { withTimezone: true }),
  report: jsonb('report'),
  error: text('error'),
});
