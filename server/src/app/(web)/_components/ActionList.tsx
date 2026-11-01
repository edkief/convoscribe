'use client';

import { useState, type FormEvent } from 'react';
import { timestamp, type ActionItem } from '@/lib/transcript-export';
import { Avatar } from './Avatar';
import { IconCalendar, IconCheckCircle, IconCircle, IconPencil, IconTrash } from './Icons';
import { SeekTime } from './Player';
import { useSaved, useSessionEditor } from './SessionEditor';
import { ConfirmButton } from './TrashActions';

/** A `yyyy-mm-dd` due date as words, like `Fri 6 Nov`. */
function dueDate(value: string): string {
  const ms = Date.parse(`${value}T00:00:00Z`);
  if (Number.isNaN(ms)) return value;
  return new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(ms);
}

/** Its text, owner and due date as fields. Blank text can't be saved; a blank owner or date is cleared. */
function ActionForm({ item, onDone }: { item: ActionItem; onDone: (saved: ActionItem | null) => void }) {
  const editor = useSessionEditor();
  const [text, setText] = useState(item.text);
  const [owner, setOwner] = useState(item.owner ?? '');
  const [due, setDue] = useState(item.dueDate ?? '');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!editor || !text.trim()) return;
    setBusy(true);
    const message = await editor.save({ kind: 'action', at: item.at, text, owner: owner || null, dueDate: due || null });
    setBusy(false);
    setError(message);
    if (!message) onDone({ ...item, text: text.trim(), owner: owner.trim() || null, dueDate: due || null });
  };
  return (
    <form className="action-form" onSubmit={submit}>
      <label className="field">
        <span>Action</span>
        <input className="edit-input" autoFocus value={text} disabled={busy} onChange={(event) => setText(event.target.value)} />
      </label>
      <label className="field">
        <span>Owner</span>
        <input className="edit-input" value={owner} disabled={busy} onChange={(event) => setOwner(event.target.value)} />
      </label>
      <label className="field">
        <span>Due</span>
        <input className="edit-input" type="date" value={due} disabled={busy} onChange={(event) => setDue(event.target.value)} />
      </label>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
      <div className="action-form-buttons">
        <button type="submit" className="button primary" disabled={busy || !text.trim()}>
          Save
        </button>
        <button type="button" className="button ghost" disabled={busy} onClick={() => onDone(null)}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function ActionRow({ item: from, tones }: { item: ActionItem; tones: Record<string, number> }) {
  const editor = useSessionEditor();
  const [item, setItem] = useSaved(from);
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const toggle = async () => {
    const done = !item.done;
    const message = await editor!.save({ kind: 'actionDone', at: item.at, done });
    setError(message);
    if (!message) setItem({ ...item, done });
  };
  const icon = item.done ? <IconCheckCircle size={20} /> : <IconCircle size={20} />;
  return (
    <li className={item.done ? 'action done' : 'action'}>
      {editor ? (
        <button type="button" className="action-check action-toggle" role="checkbox" aria-checked={item.done} aria-label={item.text} onClick={() => void toggle()}>
          {icon}
        </button>
      ) : (
        <span className="action-check" aria-label={item.done ? 'Done' : 'Open'}>
          {icon}
        </span>
      )}
      <div className="action-body">
        {editing ? (
          <ActionForm
            item={item}
            onDone={(saved) => {
              if (saved) setItem(saved);
              setEditing(false);
            }}
          />
        ) : (
          <>
            <p className="action-text">{item.text}</p>
            <div className="action-meta">
              {item.owner && (
                <span className="meta-item">
                  <Avatar name={item.owner} tone={tones[item.owner.toLowerCase()] ?? 7} size="sm" />
                  {item.owner}
                </span>
              )}
              {item.dueDate && (
                <span className="meta-item">
                  <IconCalendar size={14} />
                  Due {dueDate(item.dueDate)}
                </span>
              )}
              {item.atMs !== null && <SeekTime ms={item.atMs} label={timestamp(item.atMs)} />}
            </div>
            {error && (
              <p className="inline-error" role="alert">
                {error}
              </p>
            )}
          </>
        )}
      </div>
      {editor && !editing && (
        <div className="action-tools">
          <button type="button" className="icon-button" aria-label={`Edit action: ${item.text}`} title="Edit" onClick={() => setEditing(true)}>
            <IconPencil size={15} />
          </button>
          <ConfirmButton
            label={<IconTrash size={15} />}
            ariaLabel={`Delete action: ${item.text}`}
            className="icon-button"
            title="Delete this action?"
            confirm="Delete"
            onConfirm={() => editor.save({ kind: 'actionDelete', at: item.at })}
          >
            <p>“{item.text}” is removed here and from your devices when they next sync. A new summary won’t bring it back.</p>
          </ConfirmButton>
        </div>
      )}
    </li>
  );
}

/** The session's action items (#80): tick, edit or delete them where it can be edited (#84). */
export function ActionList({ items, tones }: { items: ActionItem[]; tones: Record<string, number> }) {
  return (
    <ul className="actions">
      {items.map((item) => (
        <ActionRow key={item.at} item={item} tones={tones} />
      ))}
    </ul>
  );
}
