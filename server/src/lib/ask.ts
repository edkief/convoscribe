import type { LlmMessage } from './llm-client';
import { questionFromExcerptsMessages, questionMessages, selectExcerpts, type Excerpts } from './qa-prompt';
import type { Chat } from './summarise';
import { chunkTranscript, transcriptText, type PromptLine } from './summary-prompt';
import { sessionView, spoken } from './transcript-export';

type Json = Record<string, unknown>;

/** Chunks of a sixth of the budget let an answer draw on several far-apart moments (the app's `AskViewModel`). */
export const CHUNKS_PER_BUDGET = 6;

/** The longest question taken, so a pasted page can't fill the prompt. */
export const QUESTION_MAX = 4000;

const isQa = (m: unknown): m is Json => !!m && typeof m === 'object' && (m as Json).kind === 'QA';

/** What a question is answered from, read from a session manifest: its spoken lines and its Q&A so far as turns. */
export function questionSource(manifest: Json): { lines: PromptLine[]; history: LlmMessage[] } {
  const view = sessionView(manifest);
  const messages = Array.isArray(manifest.messages) ? manifest.messages : [];
  return {
    lines: spoken(view).map(({ startMs, endMs, speaker, text }) => ({ startMs, endMs, speaker, text })),
    history: messages
      .filter(isQa)
      .filter((m) => typeof m.content === 'string' && (m.role === 'USER' || m.role === 'ASSISTANT'))
      .map((m) => ({ role: m.role === 'USER' ? 'user' : 'assistant', content: m.content as string })),
  };
}

export type AnswerInput = {
  lines: PromptLine[];
  history: LlmMessage[];
  question: string;
  maxChars: number;
  chat: Chat;
  signal?: AbortSignal;
  /** The answer written so far, after each piece. */
  onText?: (text: string) => void;
};

/**
 * The model's answer to `question`, as the app's `AskViewModel.answer` gets
 * it: with the whole transcript when it fits `maxChars`, else with the
 * excerpts most relevant to the question and to the question before it (a
 * follow-up like "who said that?" shares few words with the transcript),
 * which are returned too, for the coverage the answer records.
 */
export async function answerQuestion({ lines, history, question, maxChars, chat, signal, onText }: AnswerInput): Promise<{ answer: string; excerpts: Excerpts | null }> {
  const transcript = transcriptText(lines);
  let excerpts: Excerpts | null = null;
  let messages: LlmMessage[];
  if (transcript.length <= maxChars) {
    messages = questionMessages(transcript, maxChars, history, question);
  } else {
    const chunks = chunkTranscript(lines, Math.max(1, Math.floor(maxChars / CHUNKS_PER_BUDGET)), 0);
    const previous = history.findLast((m) => m.role === 'user' && m.content !== question);
    excerpts = selectExcerpts(chunks, [question, previous?.content].filter(Boolean).join(' '), maxChars);
    messages = questionFromExcerptsMessages(excerpts, history, question);
  }
  let answer = '';
  for await (const piece of chat(messages, signal)) {
    answer += piece;
    onText?.(answer);
  }
  return { answer, excerpts };
}

/**
 * Save a question and its answer into a session manifest, as the app's
 * `AskViewModel` does: a USER then an ASSISTANT QA message, the answer with
 * the span its excerpts covered when the transcript was over the budget.
 * Devices merge Q&A messages by kind, role and `createdAt`, and list them by
 * `createdAt`, so both times are kept after every message already there.
 */
export function applyQa(manifest: Json, { question, answer, askedAt, now, excerpts }: { question: string; answer: string; askedAt: number; now: number; excerpts: Excerpts | null }) {
  const messages: unknown[] = Array.isArray(manifest.messages) ? manifest.messages : [];
  const latest = Math.max(0, ...messages.map((m) => (m && typeof m === 'object' && typeof (m as Json).createdAt === 'number' ? ((m as Json).createdAt as number) : 0)));
  const asked = Math.max(askedAt, latest + 1);
  manifest.messages = [
    ...messages,
    { kind: 'QA', role: 'USER', content: question, createdAt: asked },
    {
      kind: 'QA',
      role: 'ASSISTANT',
      content: answer,
      createdAt: Math.max(now, asked + 1),
      ...(excerpts ? { coveredMs: excerpts.coveredMs, totalMs: excerpts.totalMs } : {}),
    },
  ];
}
