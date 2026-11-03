CREATE TABLE "talking_point_lists" (
	"owner" text NOT NULL,
	"sync_id" text NOT NULL,
	"manifest_hash" text NOT NULL,
	"name" text NOT NULL,
	"points" text[] DEFAULT '{}'::text[] NOT NULL,
	"created_at" bigint NOT NULL,
	"changed_at" bigint NOT NULL,
	CONSTRAINT "talking_point_lists_owner_sync_id_pk" PRIMARY KEY("owner","sync_id")
);
