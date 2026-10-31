CREATE TABLE "session_index" (
	"owner" text NOT NULL,
	"sync_id" text NOT NULL,
	"manifest_hash" text NOT NULL,
	"title" text,
	"created_at" bigint NOT NULL,
	"duration_ms" bigint,
	"language" text,
	"status" text,
	"tags" text[] DEFAULT '{}'::text[] NOT NULL,
	"speaker_count" integer,
	"speakers" text[] DEFAULT '{}'::text[] NOT NULL,
	"preview" text,
	"open_actions" integer DEFAULT 0 NOT NULL,
	"line_count" integer,
	"audio_sha256" text,
	"audio_size" bigint,
	"clean_audio_sha256" text,
	"clean_audio_size" bigint,
	"indexed_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "session_index_owner_sync_id_pk" PRIMARY KEY("owner","sync_id")
);
--> statement-breakpoint
CREATE INDEX "session_index_owner_created" ON "session_index" USING btree ("owner","created_at" DESC NULLS LAST,"sync_id" DESC NULLS LAST);--> statement-breakpoint
CREATE INDEX "session_index_tags" ON "session_index" USING gin ("tags");