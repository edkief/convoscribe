'use client';

import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react';
import { coverageMinutes } from '@/lib/qa-prompt';
import type { Message } from '@/lib/transcript-export';
import { IconSparkle, IconTrash } from './Icons';
import { Markdown } from './Markdown';
import { readEvents } from './ndjson';
import { useSessionEditor } from './SessionEditor';
import { ConfirmButton } from './TrashActions';

type Event = { text?: string; done?: boolean; revision?: number; error?: string };

/** What a failed question says, by the server's error code. */
function failure(code: string): string {
  switch (code) {
    case 'conflict':
      return 'Not saved: this session changed somewhere else. Reload the page to see the latest version.';
    case 'not_found':
      return 'Not saved: this session was deleted.';
    case 'newer_schema':
      return 'Questions can’t be asked about this session here.';
    case 'empty_transcript':
      return 'This session has no transcript to ask about.';
    case 'empty_reply':
      return 'The language model wrote nothing. Try again.';
    case 'not_offered':
      return 'This server no longer offers a language model.';
    case 'upstream_rejected':
    case 'upstream_error':
    case 'upstream_unreachable':
      return 'The language model could not be reached or failed. Try again later.';
    case 'unreachable':
      return 'The server could not be reached. Try again.';
    default:
      return 'That did not work. Try again.';
  }
}

/** Q&A messages as question and answer pairs, in order. */
function pairs(messages: Message[]) {
  const out: { question?: Message; answer?: Message }[] = [];
  for (const m of messages) {
    if (m.role === 'USER' || !out.length || out.at(-1)!.answer) out.push({});
    out.at(-1)![m.role === 'USER' ? 'question' : 'answer'] = m;
  }
  return out;
}

/** The note under an answer from excerpts of a long transcript, as the app words it. */
function coverage(answer: Message): string | null {
  if (answer.coveredMs === null || answer.totalMs === null) return null;
  const [covered, total] = coverageMinutes(answer.coveredMs, answer.totalMs);
  return `Based on ${covered} of ${total} ${total === 1 ? 'minute' : 'minutes'}, the parts most relevant to the question`;
}

/**
 * A session's *Q&A* tab: its questions and answers, and (#118), when the
 * server offers a language model (`ask`), a field to ask another. The answer
 * streams in; the round is then saved, with the earlier rounds sent along as
 * the conversation, as in the app. *Stop* saves nothing, and a failure keeps
 * the question in the field. *Clear* removes every question and answer.
 */
export function QaPanel({ syncId, questions, ask }: { syncId: string; questions: Message[]; ask: boolean }) {
  const editor = useSessionEditor();
  const [draft, setDraft] = useState('');
  const [running, setRunning] = useState(false);
  // The round being answered, kept on show until the page has it saved.
  const [live, setLive] = useState<{ question: string; text: string; base: Message[] } | null>(null);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  const rounds = pairs(questions);
  const canAsk = !!editor && ask;
  const shown = live && (running || live.base === questions) ? live : null;

  const run = async () => {
    const question = draft.trim();
    if (!editor || !question || running) return;
    const controller = new AbortController();
    abort.current = controller;
    setRunning(true);
    setLive({ question, text: '', base: questions });
    setError(null);
    let outcome: string | null = 'unknown';
    try {
      const res = await fetch(`/api/web/v1/sessions/${encodeURIComponent(syncId)}/questions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseRevision: editor.revision(), question }),
        signal: controller.signal,
      });
      const refused = await readEvents<Event>(res, (event) => {
        if (event.text !== undefined) setLive((l) => l && { ...l, text: event.text! });
        if (event.error) outcome = event.error;
        if (event.done && event.revision !== undefined) {
          outcome = null;
          setDraft('');
          editor.saved(event.revision);
        }
      });
      if (refused) outcome = refused;
    } catch {
      outcome = controller.signal.aborted ? null : 'unreachable';
    }
    abort.current = null;
    setRunning(false);
    if (outcome === 'conflict') editor.markStale();
    if (outcome || controller.signal.aborted) setLive(null);
    if (outcome) setError(failure(outcome));
  };

  const submit = (event: FormEvent) => {
    event.preventDefault();
    void run();
  };

  const onKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void run();
    }
  };

  return (
    <>
      {rounds.length === 0 && !shown ? (
        <p className="muted qa-empty">Questions and answers about the transcript appear here.</p>
      ) : (
        <ul className="qa-list">
          {rounds.map((qa, i) => {
            const note = qa.answer && coverage(qa.answer);
            return (
              <li key={i} className="qa">
                {qa.question && <p className="question">{qa.question.content}</p>}
                {qa.answer && <Markdown text={qa.answer.content} />}
                {note && <p className="panel-meta qa-coverage">{note}</p>}
              </li>
            );
          })}
          {shown && (
            <li className="qa qa-live" aria-live="polite" aria-busy={running}>
              <p className="question">{shown.question}</p>
              {shown.text ? <Markdown text={shown.text} /> : <p className="panel-meta">Writing the answer…</p>}
            </li>
          )}
        </ul>
      )}
      {error && (
        <p className="inline-error qa-error" role="alert">
          {error}
        </p>
      )}
      {canAsk && (
        <form className="qa-ask" onSubmit={submit}>
          <textarea
            className="edit-input"
            rows={2}
            maxLength={4000}
            value={draft}
            placeholder="Ask about this conversation…"
            aria-label="Ask about this conversation"
            readOnly={running}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={onKeyDown}
          />
          <div className="qa-buttons">
            {running ? (
              <button type="button" className="button" onClick={() => abort.current?.abort()}>
                Stop
              </button>
            ) : (
              <button type="submit" className="button primary" disabled={!draft.trim()}>
                <IconSparkle size={16} /> Ask
              </button>
            )}
          </div>
        </form>
      )}
      {editor && rounds.length > 0 && !running && (
        <div className="qa-clear">
          <ConfirmButton
            label={
              <>
                <IconTrash size={15} /> Clear
              </>
            }
            className="button ghost"
            title="Clear all questions?"
            confirm="Clear"
            onConfirm={() => editor.save({ kind: 'qaClear' })}
          >
            <p>This removes every question and answer for this session.</p>
          </ConfirmButton>
        </div>
      )}
    </>
  );
}
