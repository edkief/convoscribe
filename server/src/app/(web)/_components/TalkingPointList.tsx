'use client';

import { useState, type FormEvent } from 'react';
import { timestamp, type TalkingPoint } from '@/lib/transcript-export';
import { IconCheckCircle, IconCircle, IconPencil, IconPlus, IconTrash } from './Icons';
import { SeekTime } from './Player';
import { useSaved, useSessionEditor } from './SessionEditor';
import { ConfirmButton } from './TrashActions';

/** The app's `TalkingPoints.MAX_LEN`. */
const POINT_MAX = 300;

function PointRow({ point: from }: { point: TalkingPoint }) {
  const editor = useSessionEditor();
  const [point, setPoint] = useSaved(from);
  const [draft, setDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toggle = async () => {
    const covered = !point.covered;
    const message = await editor!.save({ kind: 'pointCovered', id: point.id, covered });
    setError(message);
    if (!message) setPoint({ ...point, covered, coveredAtMs: covered ? point.coveredAtMs : null, byUser: true });
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const text = draft?.trim();
    if (!editor || !text) return;
    if (text === point.text) return setDraft(null);
    setBusy(true);
    const message = await editor.save({ kind: 'point', id: point.id, text });
    setBusy(false);
    setError(message);
    if (!message) {
      setPoint({ ...point, text });
      setDraft(null);
    }
  };
  const state = point.covered ? 'Covered' : 'Not covered';
  const icon = point.covered ? <IconCheckCircle size={20} /> : <IconCircle size={20} />;
  return (
    <li className={point.covered ? 'action done' : 'action'}>
      {editor ? (
        <button
          type="button"
          className="action-check action-toggle"
          role="checkbox"
          aria-checked={point.covered}
          aria-label={point.text}
          title={point.covered ? 'Mark as not covered' : 'Mark as covered'}
          onClick={() => void toggle()}
        >
          {icon}
        </button>
      ) : (
        <span className="action-check" aria-hidden>
          {icon}
        </span>
      )}
      <div className="action-body">
        {draft !== null ? (
          <form className="action-form" onSubmit={submit}>
            <input
              className="edit-input"
              autoFocus
              value={draft}
              maxLength={POINT_MAX}
              disabled={busy}
              aria-label="Talking point"
              onChange={(event) => setDraft(event.target.value)}
              onKeyDown={(event) => {
                if (event.key === 'Escape') setDraft(null);
              }}
            />
            <div className="action-form-buttons">
              <button type="submit" className="button primary" disabled={busy || !draft.trim()}>
                Save
              </button>
              <button type="button" className="button ghost" disabled={busy} onClick={() => setDraft(null)}>
                Cancel
              </button>
            </div>
          </form>
        ) : (
          <p className="action-text">{point.text}</p>
        )}
        <div className="action-meta">
          <span className="point-state">{state}</span>
          {point.covered && point.coveredAtMs !== null && <SeekTime ms={point.coveredAtMs} label={timestamp(point.coveredAtMs)} />}
          {point.byUser && <span className="muted">by hand</span>}
        </div>
        {error && (
          <p className="inline-error" role="alert">
            {error}
          </p>
        )}
      </div>
      {editor && draft === null && (
        <div className="action-tools">
          <button type="button" className="icon-button" aria-label={`Edit talking point: ${point.text}`} title="Edit" onClick={() => setDraft(point.text)}>
            <IconPencil size={15} />
          </button>
          <ConfirmButton
            label={<IconTrash size={15} />}
            ariaLabel={`Remove talking point: ${point.text}`}
            className="icon-button"
            title="Remove this point?"
            confirm="Remove"
            onConfirm={() => editor.save({ kind: 'pointDelete', id: point.id })}
          >
            <p>“{point.text}” is removed from this conversation here and on your devices when they next sync. Your lists are not changed.</p>
          </ConfirmButton>
        </div>
      )}
    </li>
  );
}

/** A field to add a point at the end of the session's list. */
function AddPoint() {
  const editor = useSessionEditor();
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  if (!editor) return null;
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!text.trim()) return;
    setBusy(true);
    const message = await editor.save({ kind: 'pointAdd', text });
    setBusy(false);
    setError(message);
    if (!message) setText('');
  };
  return (
    <form className="point-add" onSubmit={submit}>
      <input
        className="edit-input"
        value={text}
        maxLength={POINT_MAX}
        disabled={busy}
        placeholder="Add a talking point"
        aria-label="New talking point"
        onChange={(event) => setText(event.target.value)}
      />
      <button type="submit" className="button" disabled={busy || !text.trim()}>
        <IconPlus size={15} />
        Add
      </button>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
    </form>
  );
}

/**
 * The session's talking points (#104): covered or not, and where each was
 * covered, to play from there. Where the session can be edited they can be
 * ticked, unticked, edited, removed and added.
 */
export function TalkingPointList({ points }: { points: TalkingPoint[] }) {
  return (
    <>
      {points.length > 0 ? (
        <ul className="actions">
          {points.map((point) => (
            <PointRow key={point.id} point={point} />
          ))}
        </ul>
      ) : (
        <p className="tp-none">No talking points for this conversation.</p>
      )}
      <AddPoint />
    </>
  );
}
