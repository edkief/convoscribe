import { createHash } from 'node:crypto';
import { blobSize } from './blobs';
import { getDocumentMeta, putDocument, type DocumentMeta } from './documents';
import { validZone } from './format';
import { HttpError, sha256Param, syncIdParam } from './http';

/** The app's `SessionManifests.SCHEMA`, the version a session made in the web app is written as. */
export const SESSION_SCHEMA = 1;

/** What the app's `asrEngine` says for a transcript made in the browser (#163). */
export const BROWSER_ENGINE = 'browser';

/** The app's `SessionRepository.SESSION_TITLE_MAX_LEN`. */
const TITLE_MAX = 100;
const MODEL_MAX = 100;

/** One transcript line as the browser sends it, with its word timings if it has them. */
export type NewLine = { startMs: number; endMs: number; text: string; tokens: { ms: number; text: string }[] };

/** A session recorded in the web app (#163): its recording, already uploaded, and its transcript. */
export type NewSession = {
  syncId: string;
  audioSha: string;
  durationMs: number;
  createdAt: number;
  language: string;
  lines: NewLine[];
  title: string | null;
  zone: string | null;
  asrModel: string | null;
};

type Json = Record<string, unknown>;

const isRecord = (value: unknown): value is Json => !!value && typeof value === 'object' && !Array.isArray(value);
const isMs = (value: unknown): value is number => Number.isSafeInteger(value) && (value as number) >= 0;
const optionalString = (value: unknown, max = Infinity): value is string | null | undefined =>
  value === undefined || value === null || (typeof value === 'string' && value.length <= max);

function parseLine(value: unknown): NewLine {
  if (!isRecord(value)) throw new HttpError(400, 'invalid_body');
  const { startMs, endMs, text, tokens = [] } = value;
  if (!isMs(startMs) || !isMs(endMs) || endMs < startMs || typeof text !== 'string' || !Array.isArray(tokens)) {
    throw new HttpError(400, 'invalid_body');
  }
  return {
    startMs,
    endMs,
    text,
    tokens: tokens.map((t) => {
      if (!isRecord(t) || !isMs(t.ms) || typeof t.text !== 'string') throw new HttpError(400, 'invalid_body');
      return { ms: t.ms, text: t.text };
    }),
  };
}

/**
 * A create request's body, or a 400 `invalid_body` (`invalid_sync_id` or
 * `invalid_sha256` for those two): `{syncId, audioSha, durationMs,
 * createdAt, language, lines, title?, zone?, asrModel?}`.
 */
export function parseNewSession(body: Json): NewSession {
  const { syncId, audioSha, durationMs, createdAt, language, lines, title, zone, asrModel } = body;
  if (typeof syncId !== 'string' || typeof audioSha !== 'string') throw new HttpError(400, 'invalid_body');
  const ok =
    isMs(durationMs) &&
    isMs(createdAt) &&
    createdAt > 0 &&
    typeof language === 'string' &&
    /^[a-z]{2,3}$/.test(language) &&
    Array.isArray(lines) &&
    optionalString(title, 10 * TITLE_MAX) &&
    optionalString(zone, 64) &&
    optionalString(asrModel, MODEL_MAX);
  if (!ok) throw new HttpError(400, 'invalid_body');
  return {
    syncId: syncIdParam(syncId),
    audioSha: sha256Param(audioSha),
    durationMs,
    createdAt,
    language,
    lines: lines.map(parseLine),
    title: title ?? null,
    zone: zone ?? null,
    asrModel: asrModel?.trim() || null,
  };
}

/** `yyyy-MM-dd` and `HH:mm` of `ms` in `zone`. */
function dateAndTime(ms: number, zone: string): { date: string; time: string } {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hourCycle: 'h23',
    })
      .formatToParts(ms)
      .map((p) => [p.type, p.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` };
}

/**
 * The title and its source by the app's `ConversationOptions.sessionTitle`:
 * a blank title is `Recording yyyy-MM-dd HH:mm` (`default`, which an
 * automatic title may replace), anything else is the user's, trimmed,
 * capped and with `{date}` and `{time}` filled in. Times are those of
 * `createdAt` in the browser's `zone`, else UTC.
 */
export function sessionTitle(title: string | null, createdAt: number, zone: string | null): { title: string; titleSource: string } {
  const { date, time } = dateAndTime(createdAt, validZone(zone ?? undefined));
  const trimmed = (title ?? '').trim();
  if (!trimmed) return { title: `Recording ${date} ${time}`, titleSource: 'default' };
  const filled = trimmed.replaceAll('{date}', date).replaceAll('{time}', time).slice(0, TITLE_MAX).trim();
  return { title: filled, titleSource: 'user' };
}

/**
 * The schema-1 manifest of a session made in the web app (#163), as the
 * app's `SessionManifests.build` writes one: every field, in the app's
 * order, so a device reads it as its own. Its speakers are still to be
 * found (`DIARIZATION_PENDING`), which a phone may then do. Built from
 * the request alone, so the same request gives the same bytes.
 */
export function buildSessionManifest(session: NewSession, audio: { sha256: string; size: number }): Json {
  const lines = [...session.lines].sort((a, b) => a.startMs - b.startMs);
  return {
    schema: SESSION_SCHEMA,
    syncId: session.syncId,
    ...sessionTitle(session.title, session.createdAt, session.zone),
    previousTitle: null,
    createdAt: session.createdAt,
    durationMs: session.durationMs,
    language: session.language,
    status: 'DIARIZATION_PENDING',
    numSpeakers: null,
    participants: null,
    asrEngine: session.asrModel ? BROWSER_ENGINE : null,
    asrModel: session.asrModel,
    transcriptEdited: false,
    summaryTemplate: null,
    cleanedWith: null,
    diarizedWith: null,
    diarizationStepMs: null,
    diarizationMs: null,
    audio: { sha256: audio.sha256, size: audio.size },
    cleanAudio: null,
    lines: lines.map((line) => ({
      startMs: line.startMs,
      endMs: line.endMs,
      text: line.text,
      speakerIndex: null,
      tokens: line.tokens.map((t) => ({ ms: t.ms, text: t.text })),
      textEdited: false,
      speakerEdited: false,
    })),
    speakers: [],
    messages: [],
    marks: [],
    tags: [],
  };
}

/**
 * Create a session from the web app (#163) once its recording is uploaded,
 * as a device would push it: devices download it, and it is listed and
 * searchable at once. `422 missing_blobs` before the recording is stored.
 * Repeating the request returns the same revision (`created` false); a
 * different session already under that `syncId`, or a deleted one, is a 409.
 */
export async function createSession(owner: string, session: NewSession): Promise<{ meta: DocumentMeta; created: boolean }> {
  const size = await blobSize(owner, session.audioSha);
  if (size === null) throw new HttpError(422, 'missing_blobs', { missing: [session.audioSha] });
  const bytes = Buffer.from(JSON.stringify(buildSessionManifest(session, { sha256: session.audioSha, size })));
  // Only tells a first create from a repeat; putDocument decides under the owner's lock.
  const before = await getDocumentMeta(owner, 'session', session.syncId);
  const meta = await putDocument(
    owner,
    'session',
    session.syncId,
    {
      manifest: bytes,
      manifestHash: createHash('sha256').update(bytes).digest('hex'),
      baseRevision: 0,
      durationMs: session.durationMs,
      changedAt: Date.now(),
    },
    { create: true },
  );
  return { meta, created: before === null };
}
