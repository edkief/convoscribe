'use client';

import { useRef, useState } from 'react';
import { readEvents } from './ndjson';
import { useSessionEditor } from './SessionEditor';

/** What a failed language-model action says (#119), by the server's error code. */
export function modelFailure(code: string): string {
  switch (code) {
    case 'conflict':
      return 'Not saved: this session changed somewhere else. Reload the page to see the latest version.';
    case 'not_found':
      return 'Not saved: this session was deleted.';
    case 'newer_schema':
      return 'This session can’t be changed here.';
    case 'empty_transcript':
      return 'This session has no transcript.';
    case 'no_open_points':
      return 'Every talking point is already covered or set by hand.';
    case 'no_marks':
      return 'Every mark already has a current note.';
    case 'empty_reply':
      return 'The language model wrote nothing. Try again.';
    case 'unreadable_reply':
      return 'The language model’s answer could not be read. Try again.';
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

/** The events every action's stream ends with. */
type Base = { done?: boolean; revision?: number; error?: string };

/**
 * Run one of a session's language-model actions (#119) at `path` under the
 * session's API: the page's revision goes along, `onEvent` sees each event,
 * and the revision saved is taken, or the reload prompt shown for a
 * conflict. `stop` cancels it, which saves nothing. Null without an editor.
 */
export function useModelRun<E extends Base>(syncId: string, path: string, onEvent?: (event: E) => void) {
  const editor = useSessionEditor();
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abort = useRef<AbortController | null>(null);
  if (!editor) return null;

  const run = async (): Promise<E | null> => {
    const controller = new AbortController();
    abort.current = controller;
    setRunning(true);
    setError(null);
    let outcome: string | null = 'unknown';
    let done: E | null = null;
    try {
      const res = await fetch(`/api/web/v1/sessions/${encodeURIComponent(syncId)}/${path}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ baseRevision: editor.revision() }),
        signal: controller.signal,
      });
      const refused = await readEvents<E>(res, (event) => {
        onEvent?.(event);
        if (event.error) outcome = event.error;
        if (event.done && event.revision !== undefined) {
          outcome = null;
          done = event;
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
    if (outcome) setError(modelFailure(outcome));
    return done;
  };

  return { running, error, run, stop: () => abort.current?.abort() };
}
