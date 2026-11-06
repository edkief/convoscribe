'use client';

import { useState } from 'react';
import { timestamp, type Mark } from '@/lib/transcript-export';
import { IconSparkle } from './Icons';
import { SeekTime } from './Player';
import { useModelRun } from './useModelRun';

type NotesEvent = { progress?: { done: number; total: number }; done?: boolean; revision?: number; written?: number; error?: string };

/**
 * *Write notes* (#119): the server's language model writes the full note of
 * each mark without a current one, from the transcript around it. They are
 * saved together once all are written; *Stop* saves none.
 */
function WriteNotes({ syncId, missing }: { syncId: string; missing: number }) {
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const action = useModelRun<NotesEvent>(syncId, 'mark-notes', (event) => {
    if (event.progress) setProgress(event.progress);
  });
  if (!action) return null;
  const run = async () => {
    setProgress(null);
    await action.run();
  };
  const writing = progress && progress.done < progress.total ? `Writing note ${progress.done + 1} of ${progress.total}…` : 'Writing the notes…';
  return (
    <div className="model-action">
      {action.running ? (
        <>
          <span className="model-busy" aria-live="polite">
            {writing}
          </span>
          <button type="button" className="button" onClick={action.stop}>
            Stop
          </button>
        </>
      ) : (
        <button type="button" className="button" onClick={() => void run()}>
          <IconSparkle size={15} /> Write notes
        </button>
      )}
      {!action.running && <span className="muted">{missing === 1 ? '1 mark needs a note.' : `${missing} marks need a note.`}</span>}
      {action.error && (
        <p className="inline-error" role="alert">
          {action.error}
        </p>
      )}
    </div>
  );
}

/**
 * A session's marks, each with its note and, once written, its full note
 * (marked when written before the transcript changed). With `write` (#119),
 * the server's language model can write the missing and outdated full notes.
 */
export function MarkList({ marks, write }: { marks: Mark[]; write?: { syncId: string } | null }) {
  const missing = marks.filter((m) => m.expandedNote === null || m.expandedStale).length;
  return (
    <>
      {write && missing > 0 && <WriteNotes syncId={write.syncId} missing={missing} />}
      <ul className="marks">
        {marks.map((mark, i) => (
          <li key={i}>
            <div className="mark-head">
              <SeekTime ms={mark.atMs} label={timestamp(mark.atMs)} />
              <strong>{mark.note || 'Marked moment'}</strong>
            </div>
            {mark.expandedNote && (
              <p className={mark.expandedStale ? 'mark-note stale' : 'mark-note'}>
                {mark.expandedNote}
                {mark.expandedStale && <em> Written before the transcript changed.</em>}
              </p>
            )}
          </li>
        ))}
      </ul>
    </>
  );
}
