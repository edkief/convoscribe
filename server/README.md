# ConvoScribe sync server

The self-hosted server the app syncs sessions to (#67). It is a Next.js 16
app (Node, TypeScript) with Postgres for the index and S3-compatible storage
for recordings and transcripts. It lives beside the Android project and has
its own gate, image and release; nothing in Gradle sees it.

Devices upload sessions to it (#68), and download the sessions of other
devices from it (#69). It decides conflicts between devices (see *Conflict
rule* below), so every device settles on the same version. It also serves a
web app (#80, see *Web app* below) where you sign in from a browser to
read, play and download your sessions, delete them, and restore them from
the trash (#81, see *Trash and clean-up* below).

## Web app

Open the server's address in a browser (`https://sync.example.com/`).
Signing in depends on `AUTH_MODE`:

- **`api-key`**: the sign-in page asks for `SYNC_API_KEY`. It sets an
  `HttpOnly`, `SameSite=Strict` cookie (`Secure` over https) holding an
  HMAC of its issue time keyed by `SYNC_API_KEY`, never the key itself.
  A sign-in lasts 30 days; changing the key signs every browser out.
  *Sign out* clears the cookie.
- **`authz-proxy`**: the proxy signs you in before the request reaches the
  server, and adds a signed `X-Auth-Request-JWT` that `@authz-proxy/sdk`
  verifies against the proxy's JWKS. *Sign out* goes to
  `WEB_SIGN_OUT_URL`, and is hidden when that is not set.

You see only your own sessions. The one change the web app makes is
deleting a session, which moves it to the trash (#81).

On a laptop or a tablet in landscape, a sidebar lists your sessions, your
tags with how many sessions carry each, the trash, the size of the
recordings you store (trash included) and who is signed in; below 1200 px
wide it folds into a top bar, with a trash button beside *Sign out*.

- **Library** (`/`): your sessions, newest first and grouped by day (in
  the browser's time zone), 50 to a page. Each row shows the title, the
  start of the latest summary (else of the transcript), the tags, the
  speakers, how many action items are open, the length and the time.
  Filter by tag (sidebar) and by a date range. The list comes from
  Postgres (`session_index`), never from S3.
- **Session** (`/sessions/<syncId>`): title, date, length, language, tags
  and speakers, *Delete* (see *Trash and clean-up*), and a *Download* menu (the recording, the cleaned
  recording when there is one, the transcript as Markdown, and subtitles
  as SRT or WebVTT). The transcript fills the page as speaker turns, each
  mark between them where it was made; beside it, a panel holds the
  latest summary (earlier ones folded away), the action items, the marks
  and the questions and answers, one tab each. A player stays at the
  bottom: play, back 10 s, forward 30 s, a timeline showing the marks,
  speed and mute; space plays and pauses, the arrow keys step 5 s. Tap a
  sentence, a mark or an action item's time to play from there. The
  sentence being played is highlighted and kept in view; scroll away and
  *Back to current line* returns to it.
- **Trash** (`/trash`): sessions deleted in the last 30 days, here or on a
  device, the most recently deleted first, each with when it was deleted
  and when it goes for good. *Restore* brings one back; *Empty trash*
  removes them all for good.

A manifest from a newer app version is shown as far as the server
understands it, with a note.

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
| `HEAD /api/sync/v1/blobs/<sha>` | `200` with `Content-Length` if stored, `404` if not. A `200` also keeps the blob from clean-up for its grace period, for the push about to refer to it |
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
| `DELETE /api/sync/v1/documents/<kind>/<syncId>` | `Sync-Base-Revision`; `Sync-Duration-Ms`, the length of the deleted version (optional) | `200`, leaving a tombstone (`deleted: true`, a new revision); a session goes to the trash for 30 days. Deleting again is `200`. From a stale base revision the delete wins unless the current recording is longer than `Sync-Duration-Ms`, else `409` with `current`; without the header a stale delete is `409`. `404` if unknown |
| `GET /api/sync/v1/changes?since=<seq>&limit=<n>` | `limit` 1 to 500, default 100 | `{"changes":[…], "next", "more"}`: index entries written after `since`, oldest first. Pass `next` as `since` while `more` is true |

**Web** routes are what the web app's pages call. They take the browser's
sign-in (a Bearer key works too), answer `401` without it, and refuse a
`POST` or `DELETE` whose `Origin` is not this server (`X-Forwarded-Host`,
else `Host`) with `403 cross_site`. Another owner's session is `404`.

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/web/v1/sign-in` | Form field `key` (`api-key` mode) | `303` to `/` with the sign-in cookie, or to `/sign-in?error=1`. `404` in `authz-proxy` mode |
| `POST /api/web/v1/sign-out` | | `303` to `/sign-in`, clearing the cookie; in `authz-proxy` mode to `WEB_SIGN_OUT_URL` (or `/`) |
| `GET /api/web/v1/sessions/<syncId>/audio` | `?clean=1` for the cleaned recording, `?download=1` to download it; `Range: bytes=a-b` | `audio/wav`, streamed from S3 (`206` for a range), so a recording of any size never sits in memory |
| `GET /api/web/v1/sessions/<syncId>/transcript/<format>` | `format` is `md`, `srt` or `vtt` | The transcript as a download named after the session; `404` for another format |
| `DELETE /api/web/v1/sessions/<syncId>` | JSON `{"baseRevision": n}`, the revision the page shows | `200 {"syncId","revision"}`, the session in the trash and its semantic vectors deleted. `409 conflict` if it changed since that revision, `404` if unknown or already deleted |
| `POST /api/web/v1/sessions/<syncId>/restore` | | `200 {"syncId","revision"}`: the session is live again as a new revision. `404 not_in_trash` once purged, after 30 days, or for a session deleted before the trash existed |
| `DELETE /api/web/v1/trash` | | `200 {"purged"}`: every session in the trash removed for good |

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
`uploads` and `upload_parts`), `cleanup_runs`, and `session_index`, what the web app lists
and filters sessions by: title, date, length, language, status, tags,
speakers, a preview line, the number of open action items, the line count
and recording refs, copied from a session's
manifest in the same transaction as each push. It is read tolerantly (a
missing or unknown field is null) and is only for display; sync never
reads it. When the server starts it indexes, in the background and one
manifest at a time, every session whose row is missing or out of date,
such as those pushed before #80; the log says how many. The manifests stay
the source of truth, so deleting `session_index` rows is harmless: the
next start rebuilds them. S3 holds every byte, under one prefix per
owner:

- `users/<owner>/blobs/<sha256>`: a verified recording.
- `users/<owner>/docs/<kind>/<syncId>/<manifestHash>.json.gz`: a manifest.
  It is named by its hash, so two devices pushing at once never overwrite
  each other's bytes.
- `users/<owner>/uploads/<id>`: an upload being assembled, until its hash
  is checked.

### Trash and clean-up

**Trash (#81).** Every session delete, from the web app or a device, moves
the session to the trash for 30 days. Devices see the usual tombstone and
delete it; the server keeps its last manifest and the recordings it refers
to (`documents.trashed_at` and `trashed_manifest_hash`, and its
`document_blobs` rows). Restoring it from the web app writes that manifest
back as a new revision, so every device downloads it again with its
recordings; its semantic vectors are not restored, and devices index it
again. Known voices, presets, templates and vectors are deleted outright.

A device that deleted a session while offline can still send that delete
after the session was restored: the delete wins as any stale delete does
(see *Conflict rule*), and the session goes back to the trash, to be
restored again.

After 30 days, or on *Empty trash*, a session is purged: its tombstone
stays for the change feed but keeps nothing, it can no longer be
restored, and clean-up removes its data. Sessions deleted before #81 kept
nothing and cannot be restored.

**Clean-up.** Once a day the server removes what nothing uses any more:

1. sessions in the trash for more than 30 days (purged as above);
2. uploads a device started more than 7 days ago and never finished: the
   multipart upload is aborted and its rows removed;
3. recordings no live or trashed session refers to;
4. manifests that are neither a document's current one nor a trashed
   session's, and objects an interrupted upload left behind.

Anything newer than 7 days is left alone, so a push in progress never
loses what it depends on: a recording counts as new again whenever a device
is told it is stored (`HEAD`, or an upload of it). Each removal re-checks
under the owner's lock that pushes also take, deletes the database row
first and the bytes before releasing the lock. The job runs from the
server process, checking hourly whether a day has passed since the last
scheduled run, so restarts neither skip nor repeat it. Objects outside the
key layout above are never touched.

`CLEANUP` sets it to `on` (default), `dry-run` or `off`. A dry run removes
nothing and records what a run would remove. Each run is a row in
`cleanup_runs` with its report (sessions purged, and the count and bytes of
recordings, manifests and uploads) and is logged:

```sql
select started_at, dry_run, report, error from cleanup_runs order by started_at desc limit 5;
```

On a server with existing data, start with `CLEANUP=dry-run`, compare the
first report with a listing of the bucket, then switch to `on`. A bucket
lifecycle rule that aborts incomplete multipart uploads after a few days
(on AWS, `AbortIncompleteMultipartUpload`) is no longer needed, but is a
harmless backstop.

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

Open http://localhost:3000 and sign in with `SYNC_API_KEY` to use the web
app.

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
| `ADMIN_ROLES` | no | `authz-proxy` mode: comma-separated roles that make a user an admin. Default `role-authz-admin`. In `api-key` mode the one user is the admin. Nothing uses it yet (#82). |
| `WEB_SIGN_OUT_URL` | no | `authz-proxy` mode: where the web app's *Sign out* goes, e.g. the proxy's sign-out page. Without it the web app shows no *Sign out*. |
| `CLEANUP` | no | The daily storage clean-up: `on` (default), `dry-run` (report only) or `off`. See *Trash and clean-up*. |
| `PORT` | no | Default `3000`. |

### Auth modes

Devices always send `Authorization: Bearer <key>`. What the key is depends
on the mode:

- **`api-key`**: one user. The key must equal `SYNC_API_KEY` (compared in
  constant time), and the owner is `default`.
- **`authz-proxy`**: users behind authz-proxy. The key is the user's
  `sk_authz_…` API key. `@authz-proxy/sdk` resolves it through the proxy's
  `/api/auth/userinfo`, and the owner is the username.

Browsers sign in to the web app as described in *Web app*. The sync API
only ever takes the Bearer key: the web sign-in cookie does not
authenticate it.

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

Behind authz-proxy, the web app needs the proxy to pass the signed
`X-Auth-Request-JWT` header on browser requests (its ForwardAuth response
headers) and to sign with RS256 (`JWT_ASYMMETRIC_SIGNING=true`), so the
server can verify it against `<AUTHZ_PROXY_URL>/.well-known/jwks.json`.
Open `https://sync.example.com/` in a browser: the library should show,
with your sessions. If it shows the sign-in page instead, set
`AUTHZ_PROXY_DEBUG=1` on the server and the SDK logs why each request was
not signed in.

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
