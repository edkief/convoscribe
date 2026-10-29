CREATE SEQUENCE "public"."change_seq" INCREMENT BY 1 MINVALUE 1 MAXVALUE 9223372036854775807 START WITH 1 CACHE 1;--> statement-breakpoint
CREATE TABLE "blobs" (
	"owner" text NOT NULL,
	"sha256" text NOT NULL,
	"size" bigint NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "blobs_owner_sha256_pk" PRIMARY KEY("owner","sha256")
);
--> statement-breakpoint
CREATE TABLE "document_blobs" (
	"owner" text NOT NULL,
	"kind" text NOT NULL,
	"sync_id" text NOT NULL,
	"sha256" text NOT NULL,
	CONSTRAINT "document_blobs_owner_kind_sync_id_sha256_pk" PRIMARY KEY("owner","kind","sync_id","sha256")
);
--> statement-breakpoint
CREATE TABLE "documents" (
	"owner" text NOT NULL,
	"kind" text NOT NULL,
	"sync_id" text NOT NULL,
	"revision" integer NOT NULL,
	"seq" bigint DEFAULT nextval('change_seq') NOT NULL,
	"duration_ms" bigint DEFAULT 0 NOT NULL,
	"changed_at" bigint NOT NULL,
	"manifest_hash" text,
	"deleted" boolean DEFAULT false NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "documents_owner_kind_sync_id_pk" PRIMARY KEY("owner","kind","sync_id")
);
--> statement-breakpoint
CREATE TABLE "upload_parts" (
	"upload_id" text NOT NULL,
	"n" integer NOT NULL,
	"size" bigint NOT NULL,
	"etag" text NOT NULL,
	CONSTRAINT "upload_parts_upload_id_n_pk" PRIMARY KEY("upload_id","n")
);
--> statement-breakpoint
CREATE TABLE "uploads" (
	"id" text PRIMARY KEY NOT NULL,
	"owner" text NOT NULL,
	"sha256" text NOT NULL,
	"store_upload_id" text NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "upload_parts" ADD CONSTRAINT "upload_parts_upload_id_uploads_id_fk" FOREIGN KEY ("upload_id") REFERENCES "public"."uploads"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "documents_owner_seq" ON "documents" USING btree ("owner","seq");