'use client';

import { useRef, useState, type ReactNode } from 'react';
import { IconSparkle } from './Icons';
import { Markdown } from './Markdown';
import { useSessionEditor } from './SessionEditor';

type Step = { part: number; parts: number } | 'combining' | 'writing';
type Event = { step?: Step; text?: string; done?: boolean; revision?: number; error?: string };

/** What a failed summary says, by the server's error code. */
function failure(code: string): string {
  switch (code) {
    case 'conflict':
      return 'Not saved: this session changed somewhere else. Reload the page to see the latest version.';
    case 'not_found':
      return 'Not saved: this session was deleted.';
    case 'newer_schema':
      return 'This session can’t be summarised here.';
    case 'empty_transcript':
      return 'This session has no transcript to summarise.';
    case 'empty_reply':
      return 'The language model wrote nothing. Try again.';
    case 'not_offered':
      return 'This server no longer offers a language model.';
    case 'upstream_rejected':
    case 'upstream_error':
    case 'upstream_unreachable':
      return 'The language model could not be reached or failed. Try again later.';
    default:
      return 'That did not work. Try again.';
  }
}

const stepText = (step: Step | null) =>
  step === null ? 'Writing the summary…' : step === 'combining' ? 'Combining the notes…' : step === 'writing' ? 'Writing the summary from the notes…' : `Taking notes on part ${step.part} of ${step.parts}…`;

/**
 * *Summarise* on a session's Summary tab (#117): pick a template, and the
 * server's language model writes the summary, shown as it streams in. Once
 * done it is saved with its action items, and the page shows it in place of
 * `children`, the summary it had. *Stop* saves nothing.
 */
export function SummaryPanel({ syncId, templates, selected, hasSummary, children }: { syncId: string; templates: { key: string; name: string }[]; selected: string; hasSummary: boolean; children?: ReactNode }) {
  const editor = useSessionEditor();
  const [template, setTemplate] = useState(selected);
  const [running, setRunning] = useState(false);
  const [step, setStep] = useState<Step | null>(null);
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);

  if (!editor) return <>{children}</>;

  const run = async () => {
    const controller = new AbortController();
    abort.current = controller;
    setRunning(true);
    setStep(null);
    setText('');
    setError(null);
    let outcome: string | null = 'unknown';
    try {
      const res = await fetch(`/api/web/v1/sessions/${encodeURIComponent(syncId)}/summary`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseRevision: editor.revision(), template, zone: Intl.DateTimeFormat().resolvedOptions().timeZone }),
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        outcome = ((await res.json().catch(() => ({}))) as Event).error ?? 'unknown';
      } else {
        const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
        let buffered = '';
        for (;;) {
          const { value, done } = await reader.read();
          if (done) break;
          buffered += value;
          let newline;
          while ((newline = buffered.indexOf('\n')) >= 0) {
            const event = JSON.parse(buffered.slice(0, newline)) as Event;
            buffered = buffered.slice(newline + 1);
            if (event.step) setStep(event.step);
            if (event.text !== undefined) setText(event.text);
            if (event.error) outcome = event.error;
            if (event.done && event.revision !== undefined) {
              outcome = null;
              editor.saved(event.revision);
            }
          }
        }
      }
    } catch {
      outcome = controller.signal.aborted ? null : 'unreachable';
    }
    abort.current = null;
    setRunning(false);
    if (outcome === 'conflict') editor.markStale();
    if (outcome) {
      setText('');
      setError(outcome === 'unreachable' ? 'The server could not be reached. Try again.' : failure(outcome));
    }
  };

  const stop = () => {
    abort.current?.abort();
    setText('');
  };

  return (
    <>
      <div className="summarise">
        <label className="field">
          Template
          <select value={template} disabled={running} onChange={(event) => setTemplate(event.target.value)}>
            {templates.map((t) => (
              <option key={t.key} value={t.key}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        {running ? (
          <button type="button" className="button" onClick={stop}>
            Stop
          </button>
        ) : (
          <button type="button" className="button primary" onClick={() => void run()}>
            <IconSparkle size={16} /> {hasSummary ? 'Regenerate' : 'Summarise'}
          </button>
        )}
      </div>
      {error && (
        <p className="inline-error summarise-error" role="alert">
          {error}
        </p>
      )}
      {running ? (
        <div className="summarise-live" aria-live="polite" aria-busy="true">
          <p className="panel-meta">{stepText(step)}</p>
          {text && <Markdown text={text} />}
        </div>
      ) : (
        children
      )}
    </>
  );
}
