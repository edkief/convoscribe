CREATE TABLE "session_text" (
	"owner" text NOT NULL,
	"sync_id" text NOT NULL,
	"source" text NOT NULL,
	"position" integer NOT NULL,
	"chunk" integer NOT NULL,
	"start_ms" bigint,
	"speaker" text,
	"text" text NOT NULL,
	"words" "tsvector" NOT NULL,
	CONSTRAINT "session_text_owner_sync_id_source_position_chunk_pk" PRIMARY KEY("owner","sync_id","source","position","chunk")
);
--> statement-breakpoint
ALTER TABLE "session_index" ADD COLUMN "text_version" integer DEFAULT 0 NOT NULL;--> statement-breakpoint
CREATE INDEX "session_text_words" ON "session_text" USING gin ("words");