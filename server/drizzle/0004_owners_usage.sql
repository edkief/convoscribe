CREATE TABLE "owners" (
	"owner" text PRIMARY KEY NOT NULL,
	"first_seen_at" timestamp with time zone DEFAULT now() NOT NULL,
	"last_seen_at" timestamp with time zone DEFAULT now() NOT NULL,
	"quota_bytes" bigint
);
--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "manifest_size" bigint;--> statement-breakpoint
-- Everyone with data so far (#82), last seen when their data last changed.
INSERT INTO "owners" ("owner", "first_seen_at", "last_seen_at")
SELECT "owner", min("seen"), max("seen") FROM (
  SELECT "owner", "updated_at" AS "seen" FROM "documents"
  UNION ALL SELECT "owner", "created_at" FROM "blobs"
  UNION ALL SELECT "owner", "created_at" FROM "uploads"
) AS "activity" GROUP BY "owner";
