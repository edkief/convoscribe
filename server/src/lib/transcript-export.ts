/**
 * A session manifest as the web app reads it (#80), and its transcript as
 * Markdown, SRT and WebVTT. The manifest is the app's `SessionManifest`;
 * it is read tolerantly, field by field, so a manifest from a newer app
 * (or one missing fields) still shows whatever this server understands.
 */

export type FileRef = { sha256: string; size: number };

export type TranscriptLine = {
  startMs: number;
  endMs: number;
  text: string;
  speakerIndex: number | null;
  /** The speaker's label, or null for an unlabelled line. */
  speaker: string | null;
  edited: boolean;
};

export type Mark = { atMs: number; note: string | null; expandedNote: string | null; expandedStale: boolean };

export type ActionItem = { text: string; owner: string | null; dueDate: string | null; atMs: number | null; done: boolean };

export type Message = { role: string; content: string; createdAt: number | null; templateName: string | null };

export type SessionView = {
  schema: number | null;
  title: string;
  createdAt: number | null;
  durationMs: number | null;
  language: string | null;
  tags: string[];
  participants: string[];
  audio: FileRef | null;
  cleanAudio: FileRef | null;
  lines: TranscriptLine[];
  speakers: { index: number; label: string }[];
  summaries: Message[];
  questions: Message[];
  marks: Mark[];
  actionItems: ActionItem[];
};

/** The manifest schema this server was written against; a newer one is shown read-only. */
export const KNOWN_SCHEMA = 1;

const record = (value: unknown): Record<string, unknown> =>
  value && typeof value === 'object' && !Array.isArray(value) ? (value as Record<string, unknown>) : {};
const list = (value: unknown): Record<string, unknown>[] => (Array.isArray(value) ? value.map(record) : []);
const str = (value: unknown) => (typeof value === 'string' ? value : null);
const num = (value: unknown) => (typeof value === 'number' && Number.isFinite(value) ? value : null);
const strings = (value: unknown) => (Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : []);
const fileRef = (value: unknown): FileRef | null => {
  const ref = record(value);
  return typeof ref.sha256 === 'string' && /^[0-9a-f]{64}$/.test(ref.sha256) && num(ref.size) !== null
    ? { sha256: ref.sha256, size: ref.size as number }
    : null;
};

const message = (m: Record<string, unknown>): Message => ({
  role: str(m.role) ?? '',
  content: str(m.content) ?? '',
  createdAt: num(m.createdAt),
  templateName: str(m.templateName),
});

/** Read a parsed session manifest. Never throws. */
export function sessionView(manifest: unknown): SessionView {
  const m = record(manifest);
  const speakers = list(m.speakers)
    .filter((s) => num(s.index) !== null)
    .map((s) => ({ index: s.index as number, label: str(s.label) ?? `Speaker ${(s.index as number) + 1}` }));
  const labels = new Map(speakers.map((s) => [s.index, s.label]));
  const lines = list(m.lines)
    .filter((l) => typeof l.text === 'string')
    .map((l) => {
      const speakerIndex = num(l.speakerIndex);
      const startMs = num(l.startMs) ?? 0;
      return {
        startMs,
        endMs: num(l.endMs) ?? startMs,
        text: l.text as string,
        speakerIndex: speakerIndex !== null && labels.has(speakerIndex) ? speakerIndex : null,
        speaker: speakerIndex !== null ? (labels.get(speakerIndex) ?? null) : null,
        edited: l.textEdited === true || l.speakerEdited === true,
      };
    })
    .sort((a, b) => a.startMs - b.startMs);
  const messages = list(m.messages);
  return {
    schema: num(m.schema),
    title: str(m.title)?.trim() || 'Untitled session',
    createdAt: num(m.createdAt),
    durationMs: num(m.durationMs),
    language: str(m.language),
    tags: strings(m.tags),
    participants: strings(m.participants),
    audio: fileRef(m.audio),
    cleanAudio: fileRef(m.cleanAudio),
    lines,
    speakers,
    summaries: messages.filter((x) => x.kind === 'SUMMARY' && x.role === 'ASSISTANT').map(message),
    questions: messages.filter((x) => x.kind === 'QA').map(message),
    marks: list(m.marks)
      .filter((x) => num(x.atMs) !== null)
      .map((x) => ({
        atMs: x.atMs as number,
        note: str(x.note),
        expandedNote: str(x.expandedNote),
        expandedStale: x.expandedStale === true,
      }))
      .sort((a, b) => a.atMs - b.atMs),
    actionItems: list(m.actionItems)
      .filter((x) => x.deleted !== true && typeof x.text === 'string')
      .map((x) => ({
        text: x.text as string,
        owner: str(x.owner),
        dueDate: str(x.dueDate),
        atMs: num(x.atMs),
        done: x.done === true,
      })),
  };
}

/**
 * `mm:ss` under an hour, `h:mm:ss` from there, as the app's
 * `TranscriptFormatter.timestamp` writes it. Negative times read as zero.
 */
export function timestamp(ms: number): string {
  const total = Math.floor(Math.max(0, ms) / 1000);
  const h = Math.floor(total / 3600);
  const mm = String(Math.floor((total % 3600) / 60)).padStart(2, '0');
  const ss = String(total % 60).padStart(2, '0');
  return h > 0 ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

/**
 * Index in `lines` of the line a mark at `atMs` belongs to: the last one
 * that had started, or the first for a mark before anything was said (the
 * app's `TranscriptFormatter.markLineIndex`). Null without lines.
 */
export function markLineIndex(lines: { startMs: number }[], atMs: number): number | null {
  if (lines.length === 0) return null;
  let index = -1;
  lines.forEach((line, i) => {
    if (line.startMs <= atMs) index = i;
  });
  return Math.max(index, 0);
}

/** The marks of `view` by the line they go above (`-1`: before any line, when there are none). */
export function marksByLine(view: Pick<SessionView, 'lines' | 'marks'>): Map<number, Mark[]> {
  const byLine = new Map<number, Mark[]>();
  for (const mark of view.marks) {
    const index = markLineIndex(view.lines, mark.atMs) ?? -1;
    byLine.set(index, [...(byLine.get(index) ?? []), mark]);
  }
  return byLine;
}

const oneLine = (text: string) => text.replace(/\s+/g, ' ').trim();

/** A session's transcript as Markdown: title, details, then each line with its marks above it. */
export function toMarkdown(view: SessionView): string {
  const out = [`# ${oneLine(view.title)}`, ''];
  const details = [
    view.createdAt !== null ? `Recorded: ${new Date(view.createdAt).toISOString().slice(0, 16).replace('T', ' ')} UTC` : null,
    view.durationMs ? `Length: ${timestamp(view.durationMs)}` : null,
    view.speakers.length ? `Speakers: ${view.speakers.map((s) => s.label).join(', ')}` : null,
    view.tags.length ? `Tags: ${view.tags.join(', ')}` : null,
  ].filter((d): d is string => d !== null);
  if (details.length) out.push(...details.map((d) => `- ${d}`), '');
  out.push('## Transcript', '');
  const byLine = marksByLine(view);
  const mark = (m: Mark) => {
    out.push(`> \u2691 [${timestamp(m.atMs)}] Mark${m.note?.trim() ? `: ${oneLine(m.note)}` : ''}`);
    for (const line of (m.expandedNote ?? '').split('\n').filter((l) => l.trim())) out.push(`> ${line.trim()}`);
    out.push('');
  };
  byLine.get(-1)?.forEach(mark);
  view.lines.forEach((line, i) => {
    byLine.get(i)?.forEach(mark);
    const head = line.speaker ? `**[${timestamp(line.startMs)}] ${oneLine(line.speaker)}:**` : `**[${timestamp(line.startMs)}]**`;
    out.push(`${head} ${oneLine(line.text)}`, '');
  });
  return out.join('\n').trimEnd() + '\n';
}

/** When each line's cue ends: its `endMs`, else the next line's start, else a second later. */
function cueEnd(lines: TranscriptLine[], i: number): number {
  const line = lines[i];
  if (line.endMs > line.startMs) return line.endMs;
  const next = lines[i + 1]?.startMs;
  return next !== undefined && next > line.startMs ? next : line.startMs + 1000;
}

const clock = (ms: number, separator: ',' | '.') => {
  const t = Math.max(0, Math.round(ms));
  const pad = (n: number, w = 2) => String(n).padStart(w, '0');
  return `${pad(Math.floor(t / 3_600_000))}:${pad(Math.floor((t % 3_600_000) / 60_000))}:${pad(Math.floor((t % 60_000) / 1000))}${separator}${pad(t % 1000, 3)}`;
};

const spoken = (view: SessionView) => view.lines.filter((l) => l.text.trim() !== '');

/** A session's transcript as SubRip subtitles, one cue per line, `Speaker: text`. */
export function toSrt(view: SessionView): string {
  const lines = spoken(view);
  return lines
    .map((line, i) => {
      const text = oneLine(line.text);
      return `${i + 1}\n${clock(line.startMs, ',')} --> ${clock(cueEnd(lines, i), ',')}\n${line.speaker ? `${oneLine(line.speaker)}: ${text}` : text}\n`;
    })
    .join('\n');
}

const vttEscape = (text: string) => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

/** A session's transcript as WebVTT, one cue per line, with the speaker as a voice span. */
export function toVtt(view: SessionView): string {
  const lines = spoken(view);
  const cues = lines.map((line, i) => {
    const text = vttEscape(oneLine(line.text));
    const voice = line.speaker ? `<v ${vttEscape(oneLine(line.speaker))}>${text}` : text;
    return `${clock(line.startMs, '.')} --> ${clock(cueEnd(lines, i), '.')}\n${voice}\n`;
  });
  return ['WEBVTT\n', ...cues].join('\n');
}

/**
 * A `Content-Disposition: attachment` value naming the file after `title`:
 * a plain ASCII `filename` for old clients and the exact one in `filename*`.
 */
export function attachment(title: string, extension: string): string {
  const base = oneLine(title).replace(/[\\/:*?"<>|\u0000-\u001f]/g, '_').slice(0, 100) || 'session';
  const name = `${base}.${extension}`;
  const ascii = name.replace(/[^\x20-\x7e]/g, '_').replace(/["\\%]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(name)}`;
}
