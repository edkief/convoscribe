CREATE TABLE "cleanup_runs" (
	"id" serial PRIMARY KEY NOT NULL,
	"trigger" text NOT NULL,
	"dry_run" boolean NOT NULL,
	"started_at" timestamp with time zone DEFAULT now() NOT NULL,
	"finished_at" timestamp with time zone,
	"report" jsonb,
	"error" text
);
--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "trashed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "trashed_manifest_hash" text;--> statement-breakpoint
-- Sessions deleted before the trash (#81) cannot be restored: drop what the web app indexed of them.
DELETE FROM "session_index" si USING "documents" d WHERE d."owner" = si."owner" AND d."kind" = 'session' AND d."sync_id" = si."sync_id" AND d."deleted";
