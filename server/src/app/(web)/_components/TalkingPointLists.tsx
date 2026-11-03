'use client';

import { useRouter } from 'next/navigation';
import { useRef, useState, type FormEvent, type ReactNode } from 'react';
import type { TalkingPointList } from '@/lib/talking-point-index';
import { IconAlert, IconArrowDown, IconArrowUp, IconChecklist, IconClose, IconPencil, IconPlus, IconTrash } from './Icons';
import { ConfirmButton } from './TrashActions';

/** The app's `TalkingPoints.MAX_LEN`. */
const POINT_MAX = 300;

type Answer = { ok: true } | { ok: false; message: string; stale?: boolean };

/** Send a change to a list, as an [Answer]: a 409 says the list changed elsewhere. */
async function send(url: string, method: string, body: unknown): Promise<Answer> {
  try {
    const res = await fetch(url, { method, headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    if (res.ok) return { ok: true };
    if (res.status === 409) {
      return { ok: false, stale: true, message: 'Not saved: this list changed somewhere else. Reload the page to see the latest version.' };
    }
    if (res.status === 404) return { ok: false, stale: true, message: 'Not saved: this list was deleted somewhere else.' };
    if (res.status === 422) return { ok: false, message: 'Not saved: this list comes from a newer app and can’t be edited here.' };
    return { ok: false, message: 'That did not work. Try again.' };
  } catch {
    return { ok: false, message: 'The server could not be reached. Try again.' };
  }
}

type Draft = { key: number; text: string };

/** Keys for points being edited, so a field keeps its focus as points move. */
let nextKey = 0;
const draft = (text: string): Draft => ({ key: nextKey++, text });

/** A list's name and points as fields: add, edit, move and remove points, then save them together. */
function ListForm({
  list,
  onSaved,
  onCancel,
  onStale,
}: {
  list: TalkingPointList | null;
  onSaved: () => void;
  onCancel: () => void;
  onStale: () => void;
}) {
  const [name, setName] = useState(list?.name ?? '');
  const [points, setPoints] = useState<Draft[]>(() => [...(list?.points ?? []), ''].map(draft));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const inputs = useRef(new Map<number, HTMLInputElement>());

  const set = (key: number, text: string) => setPoints((all) => all.map((p) => (p.key === key ? { ...p, text } : p)));
  const move = (at: number, by: number) =>
    setPoints((all) => {
      const out = [...all];
      [out[at], out[at + by]] = [out[at + by], out[at]];
      return out;
    });
  const remove = (key: number) => setPoints((all) => (all.length > 1 ? all.filter((p) => p.key !== key) : [draft('')]));
  const add = (after?: number) => {
    const point = draft('');
    setPoints((all) => {
      const at = after === undefined ? all.length : after + 1;
      return [...all.slice(0, at), point, ...all.slice(at)];
    });
    requestAnimationFrame(() => inputs.current.get(point.key)?.focus());
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) return;
    setBusy(true);
    const body = { name, points: points.map((p) => p.text).filter((t) => t.trim()) };
    const answer = list
      ? await send(`/api/web/v1/talking-points/${encodeURIComponent(list.syncId)}`, 'PUT', { baseRevision: list.revision, ...body })
      : await send('/api/web/v1/talking-points', 'POST', body);
    setBusy(false);
    if (answer.ok) return onSaved();
    setError(answer.message);
    if (answer.stale) onStale();
  };

  return (
    <form className="tp-form" onSubmit={submit} aria-label={list ? `Edit ${list.name}` : 'New list'}>
      <label className="field">
        <span>Name</span>
        <input
          className="edit-input"
          autoFocus
          value={name}
          maxLength={POINT_MAX}
          disabled={busy}
          placeholder="Weekly 1:1"
          onChange={(event) => setName(event.target.value)}
        />
      </label>
      <fieldset className="tp-points" disabled={busy}>
        <legend>Points</legend>
        <ol>
          {points.map((point, at) => (
            <li key={point.key}>
              <span className="tp-number" aria-hidden>
                {at + 1}.
              </span>
              <input
                ref={(el) => {
                  if (el) inputs.current.set(point.key, el);
                  else inputs.current.delete(point.key);
                }}
                className="edit-input"
                value={point.text}
                maxLength={POINT_MAX}
                aria-label={`Point ${at + 1}`}
                placeholder="Something to raise"
                onChange={(event) => set(point.key, event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    add(at);
                  }
                }}
              />
              <button type="button" className="icon-button" aria-label={`Move point ${at + 1} up`} title="Move up" disabled={at === 0} onClick={() => move(at, -1)}>
                <IconArrowUp size={15} />
              </button>
              <button
                type="button"
                className="icon-button"
                aria-label={`Move point ${at + 1} down`}
                title="Move down"
                disabled={at === points.length - 1}
                onClick={() => move(at, 1)}
              >
                <IconArrowDown size={15} />
              </button>
              <button type="button" className="icon-button" aria-label={`Remove point ${at + 1}`} title="Remove" onClick={() => remove(point.key)}>
                <IconClose size={15} />
              </button>
            </li>
          ))}
        </ol>
        <button type="button" className="button ghost" onClick={() => add()}>
          <IconPlus size={15} />
          Add point
        </button>
      </fieldset>
      {error && (
        <p className="inline-error" role="alert">
          {error}
        </p>
      )}
      <div className="action-form-buttons">
        <button type="submit" className="button primary" disabled={busy || !name.trim()}>
          {list ? 'Save' : 'Create list'}
        </button>
        <button type="button" className="button ghost" disabled={busy} onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function ListCard({ list, editing, setEditing, onStale }: { list: TalkingPointList; editing: boolean; setEditing: (on: boolean) => void; onStale: () => void }) {
  const router = useRouter();
  if (editing) {
    return (
      <li className="tp-card">
        <ListForm
          list={list}
          onSaved={() => {
            setEditing(false);
            router.refresh();
          }}
          onCancel={() => setEditing(false)}
          onStale={onStale}
        />
      </li>
    );
  }
  const count = list.points.length;
  return (
    <li className="tp-card">
      <div className="tp-card-head">
        <h2>{list.name}</h2>
        <span className="tp-count">{count === 1 ? '1 point' : `${count} points`}</span>
        <div className="action-tools">
          <button type="button" className="icon-button" aria-label={`Edit ${list.name}`} title="Edit" onClick={() => setEditing(true)}>
            <IconPencil size={15} />
          </button>
          <ConfirmButton
            label={<IconTrash size={15} />}
            ariaLabel={`Delete ${list.name}`}
            className="icon-button"
            title="Delete this list?"
            confirm="Delete"
            onConfirm={async () => {
              const answer = await send(`/api/web/v1/talking-points/${encodeURIComponent(list.syncId)}`, 'DELETE', { baseRevision: list.revision });
              if (answer.ok) {
                router.refresh();
                return null;
              }
              if (answer.stale) onStale();
              return answer.message;
            }}
          >
            <p>“{list.name}” is removed here and from your devices when they next sync. Conversations it was attached to keep their points.</p>
          </ConfirmButton>
        </div>
      </div>
      {count > 0 ? (
        <ol className="tp-list">
          {list.points.map((point, at) => (
            <li key={at}>{point}</li>
          ))}
        </ol>
      ) : (
        <p className="tp-none">No points yet.</p>
      )}
    </li>
  );
}

/** The *Talking points* page's lists (#104): create, edit and delete them; a list changed elsewhere asks to reload. */
export function TalkingPointLists({ lists }: { lists: TalkingPointList[] }) {
  const router = useRouter();
  const [editing, setEditing] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  let body: ReactNode;
  if (lists.length === 0 && editing !== 'new') {
    body = (
      <div className="empty-state">
        <span className="empty-icon">
          <IconChecklist size={26} />
        </span>
        <h2>No talking-point lists yet</h2>
        <p>Write down what you mean to raise. On a device, attach the list before Start and it ticks itself off as the points come up.</p>
      </div>
    );
  } else {
    body = (
      <ul className="tp-cards">
        {editing === 'new' && (
          <li className="tp-card">
            <ListForm
              list={null}
              onSaved={() => {
                setEditing(null);
                router.refresh();
              }}
              onCancel={() => setEditing(null)}
              onStale={() => setStale(true)}
            />
          </li>
        )}
        {lists.map((list) => (
          <ListCard
            key={list.syncId}
            list={list}
            editing={editing === list.syncId}
            setEditing={(on) => setEditing(on ? list.syncId : null)}
            onStale={() => setStale(true)}
          />
        ))}
      </ul>
    );
  }
  return (
    <>
      <header className="page-head">
        <div>
          <p className="eyebrow">Library</p>
          <h1>Talking points</h1>
          <p className="page-intro">
            Lists of what to raise in a conversation. Changes reach your devices at their next sync; a conversation keeps its own copy
            of the points.
          </p>
        </div>
        {editing !== 'new' && (
          <button type="button" className="button primary" onClick={() => setEditing('new')}>
            <IconPlus size={16} />
            New list
          </button>
        )}
      </header>
      {stale && (
        <div className="edit-stale" role="alert">
          <IconAlert size={16} />
          <span>A list changed on a device or in another tab. Nothing was overwritten.</span>
          <button type="button" className="button" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      )}
      {body}
    </>
  );
}
