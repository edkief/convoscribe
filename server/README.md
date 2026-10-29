# ConvoScribe sync server

The self-hosted server the app syncs sessions to (#67). It is a Next.js 16
app (Node, TypeScript) with Postgres for the index and S3-compatible storage
for recordings and transcripts. It lives beside the Android project and has
its own gate, image and release; nothing in Gradle sees it.

This is the scaffold (#71). The sync endpoints, tables and blob storage come
with #68.

## API

| Endpoint | Auth | Response |
|---|---|---|
| `GET /api/health` | none | `200 {"status":"ok"}` |
| `GET /api/sync/v1/whoami` | `Authorization: Bearer <key>` | `200 {"owner":"…"}`, or `401` with `WWW-Authenticate: Bearer` |

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

The tests need neither Postgres nor S3. The `authz-proxy` tests run the
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

The server releases on its own tags, separately from the app's `vX.Y.Z`:

```bash
git tag server-v0.1.0 && git push origin server-v0.1.0
```

Tekton builds the image from `server/` and pushes it as `<repo>/server`,
tagged `git-<sha>` and `latest` (see `.k8s-build.yaml`). It doesn't push a
`0.1.0` tag, because Tekton has no tags named after the git tag yet. App
release notes leave out commits that only touch `server/`.
