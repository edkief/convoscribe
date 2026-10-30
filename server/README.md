# ConvoScribe sync server

The self-hosted server the app syncs sessions to (#67). It is a Next.js 16
app (Node, TypeScript) with Postgres for the index and S3-compatible storage
for recordings and transcripts. It lives beside the Android project and has
its own gate, image and release; nothing in Gradle sees it.

Devices upload sessions to it (#68), and download the sessions of other
devices from it (#69). It decides conflicts between devices (see *Conflict
rule* below), so every device settles on the same version.

## API

| Endpoint | Auth | Response |
|---|---|---|
| `GET /api/health` | none | `200 {"status":"ok"}` |
| `GET /api/sync/v1/whoami` | `Authorization: Bearer <key>` | `200 {"owner":"…"}`, or `401` with `WWW-Authenticate: Bearer` |

Every route below needs the same `Authorization: Bearer <key>` and answers
`401` without it. Each acts for the key's owner only. Errors are JSON
`{"error": "<code>", …}`.

**Blobs** are recordings, named by the lower-case hex SHA-256 of their bytes.

| Endpoint | Response |
|---|---|
| `HEAD /api/sync/v1/blobs/<sha>` | `200` with `Content-Length` if stored, `404` if not |
| `GET /api/sync/v1/blobs/<sha>` | The bytes; `Range: bytes=a-b` gives `206` |
| `POST /api/sync/v1/blobs/<sha>/uploads` | `201 {"id"}` to start an upload, or `200 {"exists":true}` |
| `GET /api/sync/v1/blobs/<sha>/uploads/<id>` | `{"parts":[{"n","size"}]}` received so far, for resuming; `404` if unknown |
| `PUT /api/sync/v1/blobs/<sha>/uploads/<id>/parts/<n>` | `204`. Parts are numbered from 1, at most 16 MB each (devices send 8 MB); sending a part again replaces it |
| `POST /api/sync/v1/blobs/<sha>/uploads/<id>/complete` | `200 {"size"}` once the assembled bytes hash to `<sha>`; `400 hash_mismatch` discards them; `409 parts_missing` if a part number is skipped |

**Documents** are synced items. Their `kind` is `session`, `voice` (one
known-voice sample), `preset`, `template` (a summary template) or
`vectors` (a session's semantic vectors, under the session's sync id)
(#70). Library items and vectors are sent with `Sync-Duration-Ms: 0`, so
the conflict rule comes down to the most recent change. The
body of a document is the app's manifest (JSON). The server reads only the
recordings it refers to (objects with a `sha256` and a `size`), which must
all be stored first.

| Endpoint | Request | Response |
|---|---|---|
| `PUT /api/sync/v1/documents/<kind>/<syncId>` | Manifest body, optionally `Content-Encoding: gzip`. Headers `Sync-Base-Revision` (0 for new), `Sync-Manifest-Hash` (SHA-256 of the uncompressed body), `Sync-Duration-Ms`, `Sync-Changed-At` (ms since epoch) | `200` with the new index entry. From a stale base revision the push is still accepted, as the next revision, if it wins the conflict rule; otherwise `409 conflict` with `current`. Repeating the current manifest is `200` with no new revision. `422 missing_blobs` with `missing`, `400 hash_mismatch` |
| `GET /api/sync/v1/documents/<kind>/<syncId>` | | The manifest, with `Sync-Revision`, `Sync-Seq`, `Sync-Duration-Ms`, `Sync-Changed-At` and `Sync-Manifest-Hash` headers. `410` with `current` if deleted, `404` if unknown |
| `DELETE /api/sync/v1/documents/<kind>/<syncId>` | `Sync-Base-Revision`; `Sync-Duration-Ms`, the length of the deleted version (optional) | `200`, leaving a tombstone (`deleted: true`, a new revision); deleting again is `200`. From a stale base revision the delete wins unless the current recording is longer than `Sync-Duration-Ms`, else `409` with `current`; without the header a stale delete is `409`. `404` if unknown |
| `GET /api/sync/v1/changes?since=<seq>&limit=<n>` | `limit` 1 to 500, default 100 | `{"changes":[…], "next", "more"}`: index entries written after `since`, oldest first. Pass `next` as `since` while `more` is true |

An index entry is `{kind, syncId, revision, seq, durationMs, changedAt,
manifestHash, deleted}`. `seq` orders every write of an owner, and writes of
one owner are serialised, so a device that saved `next` never misses one.

### Conflict rule

When a device pushes from a base revision that is no longer the current one
(another device changed the document since), `pushWins` in
`src/lib/documents.ts` compares the push with the current version: the
**longer recording** (`durationMs`) wins, then the **later `changedAt`**,
then the higher manifest hash, so a tie never flips between devices. Against
a tombstone, which keeps the length of the version it deleted, a push wins
only with a longer recording. A stale delete wins unless the current version
is longer than the one the device deleted (`deleteWins`). The loser gets
`409` with the current version and downloads it.

### Storage

Postgres holds the index (`documents`, plus `document_blobs`, `blobs`,
`uploads` and `upload_parts`). S3 holds every byte, under one prefix per
owner:

- `users/<owner>/blobs/<sha256>`: a verified recording.
- `users/<owner>/docs/<kind>/<syncId>/<manifestHash>.json.gz`: a manifest.
  It is named by its hash, so two devices pushing at once never overwrite
  each other's bytes.
- `users/<owner>/uploads/<id>`: an upload being assembled, until its hash
  is checked.

Old manifests and recordings no longer referenced are kept for now; a
clean-up job comes later. Parts of an upload a device abandoned stay in
S3 as an incomplete multipart upload, so give the bucket a lifecycle rule
that aborts incomplete multipart uploads after a few days (on AWS, an
`AbortIncompleteMultipartUpload` rule; most S3-compatible stores have an
equivalent).

## Development

Needs Node 24 and pnpm. `scripts/setup-toolchain.sh` installs both at the
pinned versions, and `source scripts/env.sh` puts them on `PATH`.

```bash
cd server
pnpm install
docker compose up -d   # Postgres on :5432, MinIO on :9000 (console :9001) with bucket "convoscribe"
cp .env.example .env
pnpm dev               # http://localhost:3000
curl -H "Authorization: Bearer dev-key-change-me-0123456789abcdef" localhost:3000/api/sync/v1/whoami
```

The gate, which CI and the image build also run:

```bash
bash scripts/server-gate.sh   # from the repo root: install, then lint, typecheck and test
pnpm check                    # the same, inside server/
```

The tests need neither Postgres nor S3: they run on PGlite (Postgres in
WebAssembly) with every migration applied, and a filesystem blob store. The
`authz-proxy` tests run the
SDK's dev proxy in-process.

### Database

Drizzle, with the schema in `src/db/schema.ts` and migrations in `drizzle/`.
The server applies pending migrations when it starts. After changing the
schema:

```bash
pnpm db:generate   # writes the next migration into drizzle/
pnpm db:migrate    # applies it to DATABASE_URL without starting the server
```

## Configuration

Environment variables only. The server checks them when it starts, and
exits with the list of problems if any is missing or invalid.

| Variable | Required | Meaning |
|---|---|---|
| `DATABASE_URL` | yes | Postgres connection URL. |
| `S3_ENDPOINT` | no | S3-compatible endpoint, e.g. `http://minio:9000`. Blank for AWS. |
| `S3_REGION` | no | Default `us-east-1`. |
| `S3_BUCKET` | yes | Bucket for session data. It must exist. |
| `S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY` | yes | S3 credentials. |
| `S3_FORCE_PATH_STYLE` | no | `true` (default; MinIO and most self-hosted stores) or `false`. |
| `AUTH_MODE` | yes | `api-key` or `authz-proxy`; see below. |
| `SYNC_API_KEY` | `api-key` mode | The key devices send, at least 32 characters (`openssl rand -hex 32`). |
| `AUTHZ_PROXY_URL` | `authz-proxy` mode | Base URL of authz-proxy, reachable from the server. |
| `PORT` | no | Default `3000`. |

### Auth modes

Devices always send `Authorization: Bearer <key>`. What the key is depends
on the mode:

- **`api-key`**: one user. The key must equal `SYNC_API_KEY` (compared in
  constant time), and the owner is `default`.
- **`authz-proxy`**: users behind authz-proxy. The key is the user's
  `sk_authz_…` API key. `@authz-proxy/sdk` resolves it through the proxy's
  `/api/auth/userinfo`, and the owner is the username.

## Deployment

The image is built from `server/` as its own context and needs only the
environment above. It listens on port 3000 as a non-root user, and its
health check is `GET /api/health`.

```bash
docker build -t convoscribe-server server
docker run --rm -p 3000:3000 --env-file server/.env convoscribe-server
```

Behind the reverse proxy, allow request bodies of at least 8 MB: sync uploads
recordings in parts of about 8 MB (#67). To check a deployment:

```bash
curl -H "Authorization: Bearer sk_authz_…" https://sync.example.com/api/sync/v1/whoami
head -c 9000000 /dev/urandom > /tmp/9mb
curl -o /dev/null -w '%{http_code}\n' -X POST --data-binary @/tmp/9mb \
  -H "Authorization: Bearer sk_authz_…" https://sync.example.com/api/health
```

A `413` from the second command is the proxy refusing the size. The server
itself answers `405`, because health only takes `GET`.

## Release

The server releases on its own `server-vX.Y.Z` tags, separately from the
app's `vX.Y.Z`. From the repo root, on `main` with a clean tree:

```bash
bash scripts/release.sh prepare-server                    # 0.1.0 -> 0.2.0, push server-v0.2.0
bash scripts/release.sh prepare-server --patch --dry-run  # print the next version only
```

It bumps `version` in `server/package.json` (minor by default; `--patch`,
`--major` or `--version X.Y.Z`), commits `chore(release): server-vX.Y.Z`,
tags it, and pushes the branch and tag together. It refuses when nothing
under `server/` changed since the previous `server-v*` tag.

Tekton builds the image from `server/` and pushes it as `<repo>/server`,
tagged `git-<sha>` and `latest` (see `.k8s-build.yaml`). It doesn't push a
`0.1.0` tag, because Tekton has no tags named after the git tag yet. App
release notes leave out commits that only touch `server/`.
