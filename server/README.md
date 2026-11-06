# ConvoScribe sync server

The self-hosted server the app syncs sessions to (#67). It is a Next.js 16
app (Node, TypeScript) with Postgres for the index and S3-compatible storage
for recordings and transcripts. It lives beside the Android project and has
its own gate, image and release; nothing in Gradle sees it.

Devices upload sessions to it (#68), and download the sessions of other
devices from it (#69). It decides conflicts between devices (see *Conflict
rule* below), so every device settles on the same version. It also serves a
web app (#80, see *Web app* below) where you sign in from a browser to
read, search, play and download your sessions (#83), delete them, and restore
them from the trash (#81, see *Trash and clean-up* below). Admins also see who uses
the server and how much they store, whether its storage can be reached, and
run clean-up and set quotas (#82, see *Admin pages* below).

It can also offer its users a language model and a transcription server
(#93, see *Language model and transcription* below), so devices reach them
with their sync key and never hold the upstream address or key. With a
language model, the web app can also summarise a session (#117, see
*Summarising a session* below), answer questions about one (#118, see
*Asking about a session* below), and suggest its title, check its talking
points and write its marks' notes (#119, see *Titles, talking points and mark
notes* below).

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

You see only your own sessions and talking-point lists. From the web app you
can edit a session (*Editing a session*, below) and delete it, which moves it to
the trash (#81), and write the talking-point lists your devices attach before
Start (#104).

On a laptop or a tablet in landscape, a sidebar holds a search field and lists
your sessions, your talking-point lists, your tags with how many sessions carry each, the trash, the
size of the recordings you store (trash included) and who is signed in; below
1200 px wide it folds into a top bar, with talking points (#110), search and
trash buttons beside *Sign out*, and for admins a button to the admin pages. On
a phone the bar shows the logo without the name, so every button fits.

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
  latest summary (earlier ones folded away), the talking points, the action
  items, the marks and the questions and answers, one tab each. A player stays at the
  bottom: play, back 10 s, forward 30 s, a timeline showing the marks,
  speed and mute; space plays and pauses, the arrow keys step 5 s. Tap a
  sentence, a mark, an action item's time or where a talking point was
  covered to play from there. The
  sentence being played is highlighted and kept in view; scroll away and
  *Back to current line* returns to it. With a language model offered, the
  *Summary* tab is always there and can write the summary (*Summarising a
  session*, below), and the *Q&A* tab is always there and can answer
  questions (*Asking about a session*, below).
- **Search** (`/search?q=`): finds words in your transcripts, summaries,
  session titles and speaker names, by the same rules as the app's search:
  case and accents don't matter (`reunion` finds *Réunion*), the start of a
  word is enough, and every word has to be in the same line, summary, title
  or speaker name. Results are grouped per session, newest first, 20 sessions
  to a page, with the matched words marked. A session lists up to 50 matching
  lines, and says how many more there are. A line opens the session scrolled
  to it and highlighted, with the player there but not playing (`?line=`). A
  summary opens the *Summary* tab (`?tab=summary`, with `&earlier=1` to unfold
  older summaries). Search reads Postgres only, and never finds another
  user's sessions or anything in the trash. Tag and date filters, and meaning
  rather than words, are for the app.
- **Talking points** (`/talking-points`, #104): your talking-point lists, by
  name, each with its points in order. *New list* and the pencil open the same
  form: a name, and the points, one field each, with buttons to move one up or
  down or remove it (Enter in a point adds one after it). *Paste points*, or
  pasting several lines into a point, adds a point per line: bullets and numbers
  are dropped and points already in the list are left out (#114). *Save* sends
  the whole list. Names and points are cleaned as in the app (spaces collapsed, at most
  300 characters, blank points dropped). Deleting a list removes it from your
  devices; conversations it was attached to keep their own copy of the points.
  The page reads Postgres (`talking_point_lists`), never S3. A list is a library
  item like a summary template: a web change is a new revision of its document,
  which devices download at their next sync, and the most recent change wins. A
  save or delete from a page older than the list is refused, and the page asks
  you to reload.
- **Trash** (`/trash`): sessions deleted in the last 30 days, here or on a
  device, the most recently deleted first, each with when it was deleted
  and when it goes for good. *Restore* brings one back; *Empty trash*
  removes them all for good.

A manifest from a newer app version is shown as far as the server
understands it, with a note.

### Editing a session

On a session's page you can rename it, add and remove tags, rename a speaker,
correct a line (*Edit text*, then tap the line; Ctrl/Cmd+Enter saves), tick,
edit or delete an action item, and tick, untick, edit, remove or add a talking
point (#104). A title the model wrote, here or in the app, can be put back to
the one it replaced (*Put back*, #119). A point ticked or unticked here counts as by hand, so the app's
automatic checks (#103) leave it alone; unticking forgets when it was covered.
The *Talking points* tab shows when the session has points, or, to add some,
when the panel has other tabs. Reordering a session's points is for the app. Splitting, merging or deleting lines and changing
a line's speaker are for the app.

An edit is one more device's change: a new revision of the manifest, which
devices download when they next sync and don't push back. It follows the app's
rules, so a corrected line gets the same text and word timings as in the app
(`src/lib/token-aligner.ts`, tested with the same cases as the app's
`TokenAligner`), and fields this server doesn't know are kept. A session from a
newer app than the server knows is shown but can't be edited.

An edit is made from the revision the page shows. If the session changed since,
on a device or in another tab, nothing is overwritten and the page asks you to
reload. In the other direction, a device with unsynced changes merges them with
the web edit (#90, see *Conflict rule*), so both survive. An app older than that
can still replace a web edit, because the longer recording, then the later change,
wins for the whole session.

### Summarising a session

When the server offers a language model (`LLM_UPSTREAM_URL`, #117), a
session's *Summary* tab has a template picker and *Summarise*, or
*Regenerate* once there is a summary. The templates are the app's built-in
ones and your own, synced from your devices. The session's template is chosen
to start with, else *Meeting*. The summary streams in as the model writes it.
*Stop* cancels the model and saves nothing; so does closing the page.

The server writes the summary as the app does, with the same prompts. Your
speaker names and marks are included, and the model is told the recording
date in the browser's time zone, so it can write out due dates. A transcript
longer than `LLM_MAX_TRANSCRIPT_CHARS` is summarised in parts: notes on each
part, combined while they are still too long, then the summary from them. The
tab says which part it is on.

Once it is done, the summary is saved as a web edit (*Editing a session*):
one new revision with this summary in place of the earlier ones, its action
items merged in and the template kept for next time. Action items you ticked,
edited or deleted stay as they are; the model's others are replaced, and an
item that repeats one you kept is left out. Devices download it at their next
sync. If the session changed while the summary was being written, nothing is
saved and the page asks you to reload.

As in the app, a session whose title is still the date-based default is named
from its new summary (#119). The tab says *Naming the session…* while the model
writes the title, and summary and title are saved as the same revision. The
title it replaced can be put back. If naming fails, the summary is saved
anyway with the old title. The app's *Name conversations automatically*
setting stays on each device, so the server can't see it and always names a
session that still has its default title.

### Asking about a session

When the server offers a language model (#118), a session's *Q&A* tab has a
field to ask about the conversation. Enter or *Ask* sends the question
(Shift+Enter starts a new line), and the answer streams in under it. *Stop*
cancels the model and saves nothing, and so does closing the page. If it
fails, the question stays in the field to try again.

The server asks the model as the app does, with the same prompts. The
session's earlier questions and answers (the last three rounds) go with the
question as the conversation, so a follow-up like *who said that?* works. A
transcript longer than `LLM_MAX_TRANSCRIPT_CHARS` is answered from its parts
most relevant to the question, and to the question before it, which are
picked by matching words (BM25), as on a device. Such an answer says how much
of the conversation those parts cover: *Based on 18 of 92 minutes, the parts
most relevant to the question*.

The question and the answer are saved as a web edit (*Editing a session*):
one new revision, which devices download at their next sync. If the session
changed while the answer was being written, nothing is saved and the page
asks you to reload. Your question stays in the field. *Clear* removes every
question and answer of the session, but not its summary. A device keeps the
removal when it merges, as it does for one made in the app.

### Titles, talking points and mark notes

When the server offers a language model (#119), three smaller actions run as
they do in the app, with the same prompts:

- **Suggest title**, the sparkle beside the title: the model names the session
  from its summary, or from its transcript when there is none, in a few words
  in the session's language. The new title replaces the current one, whatever
  it was. *Put back* under the title restores the one it replaced (the title
  from before the model's first one, after several suggestions).
- **Check points**, on the *Talking points* tab when some are open: the model
  reads the whole transcript and ticks the points it finds discussed, with the
  time to play from. A transcript longer than `LLM_MAX_TRANSCRIPT_CHARS` is
  read in parts; a point found in one part isn't asked about again. Points
  ticked or unticked by hand are neither sent nor changed, and a point already
  ticked stays ticked.
- **Write notes**, on the *Marks* tab when a mark has no full note, or one
  written before the transcript changed: the model writes each from the mark's
  note and the transcript around it (a minute before, half a minute after).
  The tab counts the notes as they are written.

Each action is a web edit (*Editing a session*) saved as one new revision once
it is done, or none if nothing changed. *Stop*, or closing the page, cancels
it and saves nothing, including the notes already written. If the session
changed in the meantime, nothing is saved and the page asks you to reload.

### Admin pages

For admins only (#82): in `api-key` mode the one user, behind authz-proxy
whoever holds one of `ADMIN_ROLES`. The sidebar shows them an *Admin* section.
To anyone else the pages answer `404` and their API `403`. They show counts,
bytes and times, and never another user's titles, transcripts or audio.

- **Users** (`/admin/users`): everyone seen by this server, with their
  sessions, what is in their trash, the bytes they store, their last
  activity (noted at most every 5 minutes, from devices and the web app) and
  their quota.
- **Status** (`/admin/status`): whether Postgres and S3 can be reached;
  bytes stored across all users, split into live, in the trash, unreferenced
  and in unfinished uploads; and clean-up, with its daily mode, when it last
  ran, what it removed, and *Preview* and *Run clean-up*. Why a dependency
  can't be reached goes to the server's log, never to the page. The page
  still renders with Postgres or S3 down. `/api/health` stays a liveness
  check that touches neither.

The stored figures come from Postgres, so reading them costs no S3 calls.
They leave out earlier versions of manifests, which Postgres does not track;
a clean-up preview reports those old enough to remove. A manifest pushed
before #82 counts once a background job at startup has read its size.

**Quotas.** Set one per user on the *Users* page, in GB (powers of 1000);
empty is no limit, the default. Usage is recordings, current and trashed
manifests and parts of unfinished uploads, so a trashed session counts until
it is purged. Over quota, starting an upload or sending a part answers `507
quota_exceeded`, and the app's sync error says the quota was reached. A
recording the server already holds still counts as stored, and manifests are
never refused, so edits and deletes always work, and an upload whose parts all
arrived can still finish. Uploads running at once can pass the quota by about a
part each.

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
known-voice sample), `preset`, `template` (a summary template),
`vectors` (a session's semantic vectors, under the session's sync id)
(#70) or `talking-points` (a talking-point list, #102). Library items and vectors are sent with `Sync-Duration-Ms: 0`, so
the conflict rule comes down to the most recent change. The
body of a document is the app's manifest (JSON). The server reads only the
recordings it refers to (objects with a `sha256` and a `size`), which must
all be stored first.

| Endpoint | Request | Response |
|---|---|---|
| `PUT /api/sync/v1/documents/<kind>/<syncId>` | Manifest body, optionally `Content-Encoding: gzip`. Headers `Sync-Base-Revision` (0 for new), `Sync-Manifest-Hash` (SHA-256 of the uncompressed body), `Sync-Duration-Ms`, `Sync-Changed-At` (ms since epoch) | `200` with the new index entry. From a stale base revision the push is still accepted, as the next revision, if it wins the conflict rule; otherwise `409 conflict` with `current`. With `Sync-Merge: 1` (sessions, #90) a stale push against a live version is always `409`, for the app to merge. Repeating the current manifest is `200` with no new revision. `422 missing_blobs` with `missing`, `400 hash_mismatch` |
| `GET /api/sync/v1/documents/<kind>/<syncId>` | | The manifest, with `Sync-Revision`, `Sync-Seq`, `Sync-Duration-Ms`, `Sync-Changed-At` and `Sync-Manifest-Hash` headers. `410` with `current` if deleted, `404` if unknown |
| `DELETE /api/sync/v1/documents/<kind>/<syncId>` | `Sync-Base-Revision`; `Sync-Duration-Ms`, the length of the deleted version (optional) | `200`, leaving a tombstone (`deleted: true`, a new revision); a session goes to the trash for 30 days. Deleting again is `200`. From a stale base revision the delete wins unless the current recording is longer than `Sync-Duration-Ms`, else `409` with `current`; without the header a stale delete is `409`. `404` if unknown |
| `GET /api/sync/v1/changes?since=<seq>&limit=<n>` | `limit` 1 to 500, default 100 | `{"changes":[…], "next", "more"}`: index entries written after `since`, oldest first. Pass `next` as `since` while `more` is true |

**Language model and transcription** (#93) are offered when the operator
sets `LLM_UPSTREAM_URL` or `ASR_UPSTREAM_URL` (see *Configuration*). The
server passes a fixed set of OpenAI-compatible routes to each upstream;
nothing a caller sends picks the upstream or the path. Point the app's
language model or transcription server address at
`https://<sync server>/api/ai/v1/llm` or `…/api/ai/v1/asr`, with the sync
key as the API key.

| Endpoint | Passed to the upstream as |
|---|---|
| `GET /api/sync/v1/config` | Not passed on. `200 {"llm": {"model"} \| null, "transcription": {"model"} \| null}`: what this server offers, `model` being `LLM_MODEL` or `ASR_MODEL` (null when not set). It never names an upstream address or key. An older server answers `404`, meaning it offers nothing |
| `GET /api/ai/v1/llm/models` | `GET <LLM_UPSTREAM_URL>/models` |
| `POST /api/ai/v1/llm/chat/completions` | `POST <LLM_UPSTREAM_URL>/chat/completions` |
| `GET /api/ai/v1/asr/models` | `GET <ASR_UPSTREAM_URL>/models` |
| `POST /api/ai/v1/asr/audio/transcriptions` | `POST <ASR_UPSTREAM_URL>/audio/transcriptions` |

- The caller's `Authorization` is replaced by `Bearer <*_UPSTREAM_KEY>`, or
  dropped when no key is set (an upstream inside the cluster may need
  none). Only `Content-Type`, `Content-Length` and `Accept` are passed
  on, and only `Content-Type` comes back.
- Both bodies stream: answers arrive token by token
  (`Cache-Control: no-transform`), and a recording passes through without
  being held in memory. A caller going away, or *Stop generating*, cancels
  the upstream call. The server waits up to 15 minutes for the upstream to
  answer or to send more, as the app does.
- A service with no upstream answers `404 not_offered`.
- An upstream `400`, `404`, `413`, `422` or `429` is passed back as it is:
  the request was at fault. Anything else answers `502` with
  `upstream_rejected` (the upstream refused its key: `401` or `403`),
  `upstream_error` (another failure, or a redirect) or
  `upstream_unreachable` (no connection, or no answer in time). A `502` is
  never a bad sync key, which stays `401`. The detail is only in the server
  log, as a line starting `ai proxy:`.

**Web** routes are what the web app's pages call. They take the browser's
sign-in (a Bearer key works too), answer `401` without it, and refuse a
`POST`, `PUT`, `PATCH` or `DELETE` whose `Origin` is not this server (`X-Forwarded-Host`,
else `Host`) with `403 cross_site`. Another owner's session or list is `404`.

| Endpoint | Request | Response |
|---|---|---|
| `POST /api/web/v1/sign-in` | Form field `key` (`api-key` mode) | `303` to `/` with the sign-in cookie, or to `/sign-in?error=1`. `404` in `authz-proxy` mode |
| `POST /api/web/v1/sign-out` | | `303` to `/sign-in`, clearing the cookie; in `authz-proxy` mode to `WEB_SIGN_OUT_URL` (or `/`) |
| `GET /api/web/v1/sessions/<syncId>/audio` | `?clean=1` for the cleaned recording, `?download=1` to download it; `Range: bytes=a-b` | `audio/wav`, streamed from S3 (`206` for a range), so a recording of any size never sits in memory |
| `GET /api/web/v1/sessions/<syncId>/transcript/<format>` | `format` is `md`, `srt` or `vtt` | The transcript as a download named after the session; `404` for another format |
| `DELETE /api/web/v1/sessions/<syncId>` | JSON `{"baseRevision": n}`, the revision the page shows | `200 {"syncId","revision"}`, the session in the trash and its semantic vectors deleted. `409 conflict` if it changed since that revision, `404` if unknown or already deleted |
| `PATCH /api/web/v1/sessions/<syncId>` | JSON `{"baseRevision": n, "edit": {...}}`, with `edit` one of `{"kind":"title","title"}`, `{"kind":"tags","tags":[...]}`, `{"kind":"speaker","index","label"}`, `{"kind":"line","at","text"}`, `{"kind":"actionDone","at","done"}`, `{"kind":"action","at","text","owner","dueDate"}`, `{"kind":"actionDelete","at"}`, and for talking points (#104) `{"kind":"pointCovered","id","covered"}`, `{"kind":"point","id","text"}`, `{"kind":"pointAdd","text"}`, `{"kind":"pointDelete","id"}`, `{"kind":"qaClear"}` to remove the questions and answers (#118), and `{"kind":"titleRestore"}` to put back the title an automatic one replaced (#119); `at` is an index in the manifest's array, `id` a point's id | `200 {"syncId","revision"}`, the same revision if the edit changes nothing. `409 conflict` if it changed since that revision, `422 newer_schema` for a session from a newer app, `400 invalid_body` or `invalid_edit`, `404` if unknown or deleted |
| `POST /api/web/v1/sessions/<syncId>/summary` | JSON `{"baseRevision": n, "template"?: key, "zone"?: tz}` (#117) | NDJSON events: `{"step"}` while a long transcript is summarised in parts, `{"text"}` with the summary so far, then `{"done":true,"revision"}` once saved or `{"error"}` (`conflict`, `empty_reply`, `upstream_*`). Before it starts, a JSON error: `404 not_offered` without a language model, `409 conflict`, `422 newer_schema` or `empty_transcript` |
| `POST /api/web/v1/sessions/<syncId>/questions` | JSON `{"baseRevision": n, "question"}` (#118), the question up to 4000 characters | NDJSON events: `{"text"}` with the answer so far, then `{"done":true,"revision"}` once the question and answer are saved or `{"error"}` (`conflict`, `empty_reply`, `upstream_*`). Before it starts, a JSON error: `400 invalid_body` for a blank question, `404 not_offered` without a language model, `409 conflict`, `422 newer_schema` or `empty_transcript` |
| `POST /api/web/v1/sessions/<syncId>/title` | JSON `{"baseRevision": n}` (#119) | NDJSON: `{"done":true,"revision","title"}` once the title is saved (the same revision if it didn't change), or `{"error"}` (`conflict`, `empty_reply`, `upstream_*`). Before it starts, a JSON error: `404 not_offered` without a language model, `409 conflict`, `422 newer_schema` or `empty_transcript` (no summary and no transcript) |
| `POST /api/web/v1/sessions/<syncId>/points` | JSON `{"baseRevision": n}` (#119) | NDJSON: `{"step":{"part","parts"}}` before each part of a long transcript, then `{"done":true,"revision","ticked"}` (the same revision for none) or `{"error"}` (`conflict`, `unreadable_reply`, `upstream_*`). Before it starts, a JSON error: `404 not_offered`, `409 conflict`, `422 newer_schema`, `no_open_points` or `empty_transcript` |
| `POST /api/web/v1/sessions/<syncId>/mark-notes` | JSON `{"baseRevision": n}` (#119) | NDJSON: `{"progress":{"done","total"}}` as each note is written, then `{"done":true,"revision","written"}` once all are saved, or `{"error"}` (`conflict`, `empty_reply` when every note came back blank, `upstream_*`). Before it starts, a JSON error: `404 not_offered`, `409 conflict`, `422 newer_schema`, `no_marks` or `empty_transcript` |
| `GET /api/web/v1/talking-points` | | `200 {"lists":[{"syncId","revision","name","points","createdAt","changedAt"}]}`, by name, from Postgres |
| `POST /api/web/v1/talking-points` | JSON `{"name", "points": [...]}` | `201 {"syncId","revision"}`: a new list, as a device would push it. `400 invalid_body` for a blank name or more than 200 points |
| `PUT /api/web/v1/talking-points/<syncId>` | JSON `{"baseRevision": n, "name", "points": [...]}` | `200 {"syncId","revision"}`, the same revision if nothing changed. Fields this server doesn't know are kept. `409 conflict` if it changed since that revision, `422 newer_schema` for a list from a newer app, `404` if unknown or deleted |
| `DELETE /api/web/v1/talking-points/<syncId>` | JSON `{"baseRevision": n}` | `200 {"syncId","revision"}`, leaving a tombstone devices delete it by. `409 conflict` if it changed since that revision, `404` if unknown or already deleted |
| `POST /api/web/v1/sessions/<syncId>/summary` | JSON `{"baseRevision": n, "template"?: key, "zone"?: tz}`: a template key (`meeting`, …, or `user:<name>`; else the session's, else Meeting) and the browser's IANA time zone (#117) | NDJSON, one event a line: `{"step": {"part","parts"} \| "combining" \| "writing"}` while a long transcript is summarised in parts, `{"step": "naming"}` while a session with its default title is named (#119), `{"text"}` with the summary so far (without its action items), then `{"done": true, "revision"}` once saved or `{"error"}`: `conflict` if the session changed meanwhile, `empty_reply`, or an upstream code as for the AI proxy. Nothing is saved without `done`. Before the stream starts: `404 not_offered` without a language model, `409 conflict`, `422 newer_schema` or `empty_transcript`, `404` if unknown or deleted |
| `POST /api/web/v1/sessions/<syncId>/restore` | | `200 {"syncId","revision"}`: the session is live again as a new revision. `404 not_in_trash` once purged, after 30 days, or for a session deleted before the trash existed |
| `DELETE /api/web/v1/trash` | | `200 {"purged"}`: every session in the trash removed for good |
| `GET /api/web/v1/admin/users` | Admin | `200 {"users":[{"owner","sessions","trashed","bytes","quotaBytes","firstSeenAt","lastSeenAt"}]}` |
| `PUT /api/web/v1/admin/users` | Admin. JSON `{"owner", "quotaBytes": n}`, null for no limit | `200` with the same, `404 unknown_owner` for someone not seen |
| `GET /api/web/v1/admin/status` | Admin | `200 {"postgres":{"ok","latencyMs"},"s3":{"ok","latencyMs"},"storage":{"live","trash","unreferenced","uploads","total"}}`; `storage` is null while Postgres is down |
| `GET /api/web/v1/admin/cleanup` | Admin | `200 {"mode","run":{"running","last"},"preview":{"running","last"}}`: each `last` has `trigger`, `startedAt`, `finishedAt`, `report` and `failed`, never the error text |
| `POST /api/web/v1/admin/cleanup` | Admin. JSON `{"dryRun": bool}` | `202 {"id"}`: started in the background; `409 cleanup_running` while one of the same kind is going |

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

An app from #90 on doesn't leave a session to this rule. It sends
`Sync-Merge: 1` with a session push, so a stale push against a live version
always gets `409`; the app downloads the current version, merges it with its
own changes against the base copy it kept of what last synced
(`SessionMerge`), and pushes the result from the current revision. The merge is
on the device because the app knows the manifest schema and the server
deliberately doesn't, and the base is there because the server keeps no history
(clean-up removes old manifests). Tombstones and stale deletes still follow the
rule above, and apps without the header keep it for everything. Other kinds
don't merge.

A web edit doesn't use this rule: it is refused (`409`) from any revision but
the current one, so it never overwrites a change it didn't see.

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
next start rebuilds them.

`session_text` is what search looks in (#83): one row per transcript line,
summary, speaker and title of a live session, with the text as shown and a
`tsvector` of its words. The server splits and folds the words itself, so
matching follows the app's rules whatever the language, and no Postgres
extension is needed. A text over 4000 characters takes several rows, so a very
long transcript stays fully searchable, though words either side of a cut
count as different places. Rows are written in the same transaction as each
push and restore, and removed when a session goes to the trash; a failure to
write them is logged and never fails the push. A two-hour session takes about
1.2 MB. After an upgrade, the same background indexing fills in sessions pushed
earlier, and until it is done they are missing from results. To rebuild the
rows, set `session_index.text_version` to 0 and restart.

`talking_point_lists` is what the *Talking points* page lists (#104): each live
list's name and points, copied from its manifest in the same transaction as each
push and removed with a delete. Like `session_index` it is only for display, and
the background indexing at startup fills in lists pushed before #104.

S3 holds every byte, under one prefix per
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

Admins can also preview and run clean-up from the *Status* page (see *Admin
pages*). `CLEANUP` governs only the daily run: *Run clean-up* removes even
with `dry-run` or `off`. A run whose server died counts as going for 6 hours,
then shows as not finished.

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
| `ADMIN_ROLES` | no | `authz-proxy` mode: comma-separated roles that make a user an admin. Default `role-authz-admin`. In `api-key` mode the one user is the admin. See *Admin pages*. |
| `WEB_SIGN_OUT_URL` | no | `authz-proxy` mode: where the web app's *Sign out* goes, e.g. the proxy's sign-out page. Without it the web app shows no *Sign out*. |
| `LLM_UPSTREAM_URL` | no | An OpenAI-compatible language model the server offers its users (#93), e.g. `http://llm.llm:8080/v1`: the base its `/chat/completions` hangs off. Blank offers none. |
| `LLM_UPSTREAM_KEY` | no | The key sent to it, never to a device. Leave blank for an upstream that needs none. |
| `LLM_MODEL` | no | The model devices should use, reported by `GET /api/sync/v1/config`. The web app's summaries use it too, else the first model the upstream lists. |
| `LLM_MAX_TRANSCRIPT_CHARS` | no | How much transcript one summary or question call from the web app sends (#117, #118), as the app's *Max transcript characters*: default `24000`, at least `1000`. A longer transcript is summarised in parts, and a question about it is answered from its most relevant parts. |
| `ASR_UPSTREAM_URL`, `ASR_UPSTREAM_KEY`, `ASR_MODEL` | no | The same for an OpenAI-compatible transcription server, e.g. `http://parakeet-asr.asr:8000/v1`. A key or model without its URL is refused at startup. |
| `CLEANUP` | no | The daily storage clean-up: `on` (default), `dry-run` (report only) or `off`. An admin's *Run clean-up* is not affected. See *Trash and clean-up*. |
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

With a transcription upstream set, a whole recording is one request to
`/api/ai/v1/asr/audio/transcriptions`: about 115 MB per hour of audio, and a
silent wait while the upstream transcribes. On that path, the reverse proxy
must allow bodies of the longest recording you transcribe (about 230 MB
for two hours), must not buffer or time out a slow upload, and must wait at least
15 minutes for the answer. Traefik streams bodies and has no size limit
unless a `buffering` middleware sets one; check its entry point
`respondingTimeouts` (`readTimeout` is 60 seconds by default in recent
versions). For the
language model, the proxy must not buffer the streamed answer. The
upstream itself must accept the same upload size and time.

The server's image lifts Node's 5-minute limit on receiving a request,
through `http-timeouts.mjs` (loaded by `NODE_OPTIONS`); a connection is
instead closed after 15 minutes with nothing sent either way. Running the
server outside the image, start it with
`NODE_OPTIONS=--import=./http-timeouts.mjs` for the same.

To check, with a recording of the length you need, as the app sends it
(`Expect:` stops curl asking `100-continue`, which the app never does and
which gets past some of these limits; `--limit-rate` mimics a slow link):

```bash
curl -w '\n%{http_code} in %{time_total}s\n' -H 'Expect:' --limit-rate 200k \
  -H "Authorization: Bearer <key>" -F model=<model> -F file=@long.wav \
  https://sync.example.com/api/ai/v1/asr/audio/transcriptions
```

A `413`, or an HTML error page, comes from the reverse proxy; the server
itself answers JSON.

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
