import type { LlmMessage } from './llm-client';
import { timestamp } from './transcript-export';

/**
 * The summary prompts (#117), ported from the app's `PromptBuilder`,
 * `SummaryTemplates`, `TranscriptFormatter`, `TranscriptTruncator` and
 * `TranscriptChunker` so a summary written on the web reads like one written
 * on a device. The literal text is the contract with the model: keep it the
 * same as the app's.
 */

/** A transcript line as the prompts render it: its start, speaker label and text. */
export type PromptLine = { startMs: number; endMs: number; speaker: string | null; text: string };

/** A mark as the prompts list it. */
export type PromptMark = { atMs: number; note: string | null };

export type SummaryTemplate = { key: string; name: string; body: string; builtIn: boolean };

export const SUMMARY_PREAMBLE = 'You summarise conversation transcripts. Write in the same language as the transcript. ';

export const SUMMARY_GUARDRAILS = 'Use the speaker names exactly as they appear. Do not invent facts that are not in the transcript.';

export const ACTION_ITEMS_CONTRACT =
  'After the summary, list the action items it contains as a JSON array in a ```json code block, ' +
  'one object per item: {"text": what is to be done, "owner": the name of who will do it, or null, ' +
  '"due": the due date as YYYY-MM-DD if one was said, or null, ' +
  '"at": the transcript timestamp like "12:34" where it was agreed, or null}. ' +
  'Write [] if there are none. Write nothing after the code block.';

export const MEETING_BODY =
  'Use this structure:\n' +
  '1. Overview: one short paragraph.\n' +
  '2. Key points: bullet list.\n' +
  '3. Decisions: bullet list, or "None".\n' +
  '4. Action items: bullet list with the owner\'s name when known, or "None".\n';

export const MEETING: SummaryTemplate = { key: 'meeting', name: 'Meeting', body: MEETING_BODY, builtIn: true };

/** The app's built-in templates, Meeting first. */
export const BUILT_IN_TEMPLATES: SummaryTemplate[] = [
  MEETING,
  {
    key: 'interview',
    name: 'Interview',
    body:
      'The transcript is an interview. Use this structure:\n' +
      '1. Context: who is interviewing whom, and about what, in one or two sentences.\n' +
      '2. Questions and answers: for each main question, the question and a short summary of the answer.\n' +
      '3. Notable quotes: up to five short quotes, with the speaker\'s name, or "None".\n' +
      '4. Follow-ups: open questions or next steps, or "None".\n',
    builtIn: true,
  },
  {
    key: 'lecture',
    name: 'Lecture / talk notes',
    body:
      'The transcript is a lecture or talk. Write study notes with this structure:\n' +
      '1. Topic: one sentence.\n' +
      '2. Main ideas: a heading per idea with bullet points under it, in the order they were presented.\n' +
      '3. Definitions and key terms: bullet list, or "None".\n' +
      '4. Examples: bullet list, or "None".\n' +
      '5. Questions from the audience: each with its answer, or "None".\n',
    builtIn: true,
  },
  {
    key: 'one_on_one',
    name: '1:1',
    body:
      'The transcript is a one-to-one between two people, such as a manager and a report. Use this structure:\n' +
      '1. Topics discussed: bullet list.\n' +
      '2. Updates and progress: bullet list, or "None".\n' +
      '3. Concerns or blockers raised: bullet list, or "None".\n' +
      '4. Feedback given: bullet list, or "None".\n' +
      '5. Action items: bullet list with the owner\'s name when known, or "None".\n',
    builtIn: true,
  },
  {
    key: 'visit',
    name: 'Client / patient visit',
    body:
      'The transcript is a visit between a professional and a client or patient. Use this structure:\n' +
      '1. Reason for the visit.\n' +
      '2. What the client or patient reported: bullet list.\n' +
      '3. What the professional observed or explained: bullet list.\n' +
      '4. Recommendations or plan agreed: bullet list, or "None".\n' +
      '5. Follow-up: next appointment, tasks or documents, or "None".\n' +
      'Only record what was said; do not add advice or a diagnosis of your own.\n',
    builtIn: true,
  },
  {
    key: 'brief',
    name: 'Brief',
    body: 'Write only 3 to 5 bullet points covering the most important things said. No headings, no introduction.\n',
    builtIn: true,
  },
];

/** The summary system prompt for a template `body`: preamble, the trimmed body, guardrails, then the action-item contract. */
export function summarySystem(body: string): string {
  const trimmed = body.trim();
  return SUMMARY_PREAMBLE + (trimmed ? `${trimmed}\n` : '') + SUMMARY_GUARDRAILS + '\n' + ACTION_ITEMS_CONTRACT;
}

export const MARKS_HEADER = 'The user marked these moments as important while recording. Give them priority in the summary:\n';

export const MARK_WITHOUT_NOTE = '(no note)';

export const PART_NOTES_SYSTEM =
  'You take notes on one part of a long conversation transcript that is summarised in parts. ' +
  'Write in the same language as the transcript. ' +
  'Note every topic discussed, the key points, decisions, action items with who owns them, ' +
  'open questions and notable quotes, each with its timestamp like [12:34]. ' +
  'Be concise but leave nothing out, and write no introduction or conclusion. ' +
  SUMMARY_GUARDRAILS;

export const MERGE_NOTES_SYSTEM =
  'You combine notes taken on consecutive parts of a long conversation into one set of notes. ' +
  'Write in the same language as the notes. ' +
  'Keep every topic, key point, decision, action item with its owner, open question and quote, ' +
  'with their timestamps like [12:34]; merge what is repeated and drop nothing else. ' +
  SUMMARY_GUARDRAILS;

export const partsIntro = (parts: number) =>
  `The transcript was too long to send at once, so notes were taken on it in ${parts} parts. ` +
  'Write the summary of the whole conversation from these notes, which are in time order:\n\n';

/** The recording date as the app gives it: `yyyy-mm-dd` and its English weekday. */
export type RecordedOn = { date: string; weekday: string };

const recordedOnSection = (on: RecordedOn | null) => (on ? `The conversation was recorded on ${on.date} (${on.weekday}).\n\n` : '');

function marksSection(marks: PromptMark[]): string {
  if (marks.length === 0) return '';
  const sorted = [...marks].sort((a, b) => a.atMs - b.atMs);
  return MARKS_HEADER + sorted.map((m) => `[${timestamp(m.atMs)}] ${m.note?.trim() ? m.note : MARK_WITHOUT_NOTE}\n`).join('') + '\n';
}

/** One line as the app's `TranscriptFormatter.lineText` writes it. */
export const lineText = (line: Pick<PromptLine, 'startMs' | 'speaker' | 'text'>) =>
  line.speaker ? `[${timestamp(line.startMs)}] ${line.speaker}: ${line.text}` : `[${timestamp(line.startMs)}] ${line.text}`;

/** The lines as the app's `TranscriptFormatter.toText` writes them, without marks. */
export const transcriptText = (lines: PromptLine[]) => lines.map(lineText).join('\n');

/**
 * `transcript` as is when it fits `maxChars`, else its head and tail in whole
 * lines around a `[… N characters omitted …]` marker, never over budget (the
 * app's `TranscriptTruncator.fit`).
 */
export function fit(transcript: string, maxChars: number): string {
  if (transcript.length <= maxChars) return transcript;
  const headBudget = Math.floor(maxChars / 2);
  const tailBudget = Math.max(0, Math.floor(maxChars / 2) - 80);
  const rawHead = transcript.slice(0, headBudget);
  const headNewline = rawHead.lastIndexOf('\n');
  const head = headNewline >= 0 ? rawHead.slice(0, headNewline) : rawHead;
  const rawTail = tailBudget > 0 ? transcript.slice(transcript.length - tailBudget) : '';
  const tailNewline = rawTail.indexOf('\n');
  let tail = tailNewline >= 0 ? rawTail.slice(tailNewline + 1) : rawTail;
  if (tail.endsWith('\n')) tail = tail.slice(0, -1);
  const omitted = transcript.length - head.length - tail.length;
  return `${head}\n[… ${omitted} characters omitted …]\n${tail}`;
}

/** Messages for a one-shot summary (the app's `PromptBuilder.summary`). */
export function summaryMessages(transcript: string, maxChars: number, body = MEETING_BODY, marks: PromptMark[] = [], recordedOn: RecordedOn | null = null): LlmMessage[] {
  return [
    { role: 'system', content: summarySystem(body) },
    { role: 'user', content: recordedOnSection(recordedOn) + marksSection(marks) + 'Transcript:\n\n' + fit(transcript, maxChars) },
  ];
}

/** A run of consecutive lines rendered as text (the app's `TranscriptChunk`). */
export type TranscriptChunk = { text: string; startMs: number; endMs: number; firstLine: number; lastLine: number };

/** Notes taken on one part of a long transcript. */
export type PartNotes = { startMs: number; endMs: number; text: string };

const span = (startMs: number, endMs: number) => `[${timestamp(startMs)}–${timestamp(endMs)}]`;

/** Messages for the notes on part `part` of `parts` (the app's `PromptBuilder.summaryPart`). */
export function summaryPartMessages(chunk: TranscriptChunk, part: number, parts: number, marks: PromptMark[] = []): LlmMessage[] {
  return [
    { role: 'system', content: PART_NOTES_SYSTEM },
    { role: 'user', content: marksSection(marks) + `Transcript, part ${part} of ${parts} ${span(chunk.startMs, chunk.endMs)}:\n\n` + chunk.text },
  ];
}

/** `notes` as `Part i [a–b]:` blocks separated by blank lines. */
export const notesText = (notes: PartNotes[]) => notes.map((n, i) => `Part ${i + 1} ${span(n.startMs, n.endMs)}:\n${n.text.trim()}`).join('\n\n');

/** Messages that combine notes on consecutive parts into one set. */
export function mergeNotesMessages(notes: PartNotes[]): LlmMessage[] {
  return [
    { role: 'system', content: MERGE_NOTES_SYSTEM },
    { role: 'user', content: 'Notes, in time order:\n\n' + notesText(notes) },
  ];
}

/** Messages for the summary of a long transcript from the notes on its parts. */
export function summaryFromPartsMessages(notes: PartNotes[], maxChars: number, body = MEETING_BODY, marks: PromptMark[] = [], recordedOn: RecordedOn | null = null): LlmMessage[] {
  return [
    { role: 'system', content: summarySystem(body) },
    { role: 'user', content: recordedOnSection(recordedOn) + marksSection(marks) + partsIntro(notes.length) + fit(notesText(notes), maxChars) },
  ];
}

type Piece = { text: string; line: number; startMs: number; endMs: number };

/** `text` as is when it fits, else slices that do, cut at a space where there is one. */
function split(text: string, maxChars: number): string[] {
  if (text.length <= maxChars) return [text];
  const out: string[] = [];
  let rest = text;
  while (rest.length > maxChars) {
    const space = rest.lastIndexOf(' ', maxChars);
    const cut = space > 0 ? space : maxChars;
    out.push(rest.slice(0, cut));
    rest = rest.slice(cut).trimStart();
  }
  if (rest) out.push(rest);
  return out;
}

/**
 * Pack `lines` into chunks of at most `maxChars` characters of whole lines,
 * each after the first opening with the last lines of the previous one, up to
 * `overlapChars` (the app's `TranscriptChunker.chunk`).
 */
export function chunkTranscript(lines: PromptLine[], maxChars: number, overlapChars = Math.floor(maxChars / 20)): TranscriptChunk[] {
  if (maxChars <= 0) throw new Error('maxChars must be positive');
  const pieces: Piece[] = lines.flatMap((line, index) => split(lineText(line), maxChars).map((text) => ({ text, line: index, startMs: line.startMs, endMs: line.endMs })));
  if (pieces.length === 0) return [];
  const lastEnd = Math.max(...pieces.map((p) => Math.max(p.startMs, p.endMs)));
  const chunks: TranscriptChunk[] = [];
  let start = 0;
  for (;;) {
    let end = start;
    let length = 0;
    while (end < pieces.length) {
      const added = pieces[end].text.length + (end > start ? 1 : 0);
      if (length + added > maxChars) break;
      length += added;
      end++;
    }
    const slice = pieces.slice(start, end);
    chunks.push({
      text: slice.map((p) => p.text).join('\n'),
      startMs: slice[0].startMs,
      endMs: end < pieces.length ? pieces[end].startMs : lastEnd,
      firstLine: slice[0].line,
      lastLine: slice[slice.length - 1].line,
    });
    if (end === pieces.length) return chunks;
    start = overlapStart(pieces, start, end, overlapChars, maxChars);
  }
}

function overlapStart(pieces: Piece[], start: number, end: number, overlapChars: number, maxChars: number): number {
  const room = Math.min(overlapChars, maxChars - pieces[end].text.length - 1);
  let next = end;
  let length = 0;
  while (next - 1 > start) {
    const added = pieces[next - 1].text.length + (next < end ? 1 : 0);
    if (length + added > room) break;
    length += added;
    next--;
  }
  return next;
}
