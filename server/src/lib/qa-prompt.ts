import type { LlmMessage } from './llm-client';
import { fold } from './search-text';
import { fit, type TranscriptChunk } from './summary-prompt';

/**
 * Questions about one session (#118), ported from the app's `PromptBuilder`
 * (`question`, `questionFromExcerpts`) and `ChunkRetriever` so the web asks
 * the model what a device would: the whole transcript when it fits the
 * budget, else the parts most relevant to the question, picked with BM25.
 */

/** System prompt for a question about a transcript sent whole; the transcript follows it. */
export const QA_SYSTEM_PREFIX =
  'You answer questions about the conversation transcript below. ' +
  'Answer only from the transcript; if the answer is not in it, ' +
  'say that it is not in the transcript. ' +
  'Answer in the language of the question. ' +
  'Cite timestamps like [12:34] when useful.\n\nTranscript:\n';

/** System prompt for a question about a transcript too long to send whole; the excerpts follow it. */
export const QA_EXCERPTS_PREFIX =
  'You answer questions about a conversation. The transcript is too long to include, ' +
  'so below are only the parts of it most relevant to the question, in time order; ' +
  '[…] marks a gap between parts. Answer only from these parts; if the answer is not in them, ' +
  'say that it is not in the parts of the transcript you were given. ' +
  'Answer in the language of the question. ' +
  'Cite timestamps like [12:34] when useful.\n\nTranscript excerpts:\n';

/** Between two excerpts that are not next to each other in the transcript. */
export const EXCERPT_GAP = '\n[…]\n';

/** Past turns kept: three full exchanges, enough for follow-ups. */
export const HISTORY_WINDOW = 6;

/** The parts of a long transcript picked for one question, and how much of the conversation they span. */
export type Excerpts = { chunks: TranscriptChunk[]; coveredMs: number; totalMs: number };

/** The messages for a question about a transcript sent whole, cut to `maxChars` as a summary's is. */
export function questionMessages(transcript: string, maxChars: number, history: LlmMessage[], question: string): LlmMessage[] {
  return qa(QA_SYSTEM_PREFIX + fit(transcript, maxChars), history, question);
}

/** The messages for a question about a long transcript: the excerpts in time order, a gap marked between ones not adjacent. */
export function questionFromExcerptsMessages(excerpts: Excerpts, history: LlmMessage[], question: string): LlmMessage[] {
  let text = '';
  excerpts.chunks.forEach((chunk, index) => {
    if (index > 0) text += chunk.firstLine <= excerpts.chunks[index - 1].lastLine + 1 ? '\n' : EXCERPT_GAP;
    text += chunk.text;
  });
  return qa(QA_EXCERPTS_PREFIX + text, history, question);
}

/** `system`, the recent user and assistant turns of `history`, then the trimmed question. */
function qa(system: string, history: LlmMessage[], question: string): LlmMessage[] {
  const recent = history.filter((m) => m.role === 'user' || m.role === 'assistant').slice(-HISTORY_WINDOW);
  return [{ role: 'system', content: system }, ...recent, { role: 'user', content: question.trim() }];
}

/** BM25 term-frequency saturation. */
const K1 = 1.2;
/** BM25 length normalisation. */
const B = 0.75;
/** Room [EXCERPT_GAP] takes, counted for every chunk so the joined text never goes over budget. */
const SEPARATOR_CHARS = 5;
/** Share of the best score a chunk needs to count as a strong match. */
const STRONG_SHARE = 0.5;

/** Lower-cased words of `text`, two characters or longer (the app's `ChunkRetriever.words`). */
export const words = (text: string) => [...text.toLowerCase().matchAll(/[\p{L}\p{N}]+/gu)].map((m) => m[0]).filter((w) => w.length >= 2);

/** The words scores compare: accents removed, so "reunion" matches "Réunion" as in search. */
const terms = (text: string) => words(fold(text));

/** The BM25 score of each chunk for `query`, in the same order. */
export function scores(chunks: TranscriptChunk[], query: string): number[] {
  const wanted = new Set(terms(query));
  const docs = chunks.map((chunk) => {
    const counts = new Map<string, number>();
    for (const t of terms(chunk.text)) counts.set(t, (counts.get(t) ?? 0) + 1);
    return counts;
  });
  const lengths = docs.map((doc) => [...doc.values()].reduce((a, b) => a + b, 0));
  const average = lengths.reduce((a, b) => a + b, 0) / Math.max(1, lengths.length) || 1;
  const n = chunks.length;
  const idf = new Map([...wanted].map((term) => {
    const df = docs.filter((d) => d.has(term)).length;
    return [term, Math.log(1 + (n - df + 0.5) / (df + 0.5))] as const;
  }));
  return docs.map((doc, i) => {
    let score = 0;
    for (const term of wanted) {
      const tf = doc.get(term) ?? 0;
      if (tf > 0) score += (idf.get(term)! * tf * (K1 + 1)) / (tf + K1 * (1 - B + (B * lengths[i]) / average));
    }
    return score;
  });
}

/**
 * The chunks to answer `query` from, within `maxChars` once joined, in time
 * order (the app's `ChunkRetriever.select` for one session). The strong
 * matches go in first, best first; budget left goes to their neighbours,
 * which often hold the rest of the exchange, then to the weaker matches. When
 * no chunk shares a word with the query, chunks spread evenly over the
 * conversation are taken, so the answer still sees all of it in outline.
 */
export function selectExcerpts(chunks: TranscriptChunk[], query: string, maxChars: number): Excerpts {
  if (chunks.length === 0) return { chunks: [], coveredMs: 0, totalMs: 0 };
  const score = scores(chunks, query);
  const ranked = chunks.map((_, i) => i).filter((i) => score[i] > 0).sort((a, b) => score[b] - score[a] || a - b);
  let order: number[];
  if (ranked.length === 0) {
    order = spread(chunks, maxChars);
  } else {
    // Chunks that only share a common word score far below a real match; a strong match's neighbours are worth more.
    const strong = ranked.filter((i) => score[i] >= score[ranked[0]] * STRONG_SHARE);
    const weak = ranked.filter((i) => score[i] < score[ranked[0]] * STRONG_SHARE);
    const neighbours = strong.flatMap((i) => [i - 1, i + 1].filter((j) => j >= 0 && j < chunks.length));
    order = [...strong, ...neighbours, ...weak];
  }
  const picked = new Set<number>();
  let used = 0;
  for (const index of order) {
    if (picked.has(index)) continue;
    const cost = chunks[index].text.length + SEPARATOR_CHARS;
    if (used + cost > maxChars) continue;
    picked.add(index);
    used += cost;
  }
  const selected = [...picked].sort((a, b) => a - b).map((i) => chunks[i]);
  return { chunks: selected, coveredMs: covered(selected), totalMs: chunks[chunks.length - 1].endMs };
}

/** Chunk indices spaced evenly over the conversation, as many as about fit `maxChars`. */
function spread(chunks: TranscriptChunk[], maxChars: number): number[] {
  const average = Math.floor(chunks.reduce((sum, c) => sum + c.text.length + SEPARATOR_CHARS, 0) / chunks.length);
  const count = Math.min(Math.max(Math.floor(maxChars / Math.max(1, average)), 1), chunks.length);
  const step = chunks.length / count;
  return Array.from({ length: count }, (_, i) => Math.min(Math.floor(i * step + step / 2), chunks.length - 1));
}

/** The length of the union of the chunks' time spans; they are in time order. */
function covered(chunks: TranscriptChunk[]): number {
  let total = 0;
  let reached = -Infinity;
  for (const chunk of chunks) {
    const from = Math.max(chunk.startMs, reached);
    if (chunk.endMs > from) total += chunk.endMs - from;
    reached = Math.max(reached, chunk.endMs);
  }
  return total;
}

/**
 * Whole minutes of `coveredMs` and `totalMs` for the note under an answer
 * (the app's `coverageMinutes`): rounded, at least one, and the covered figure
 * never above the total.
 */
export function coverageMinutes(coveredMs: number, totalMs: number): [number, number] {
  const total = Math.max(1, Math.floor((totalMs + 30_000) / 60_000));
  const coveredMin = Math.min(Math.max(Math.floor((coveredMs + 30_000) / 60_000), 1), total);
  return [coveredMin, total];
}
