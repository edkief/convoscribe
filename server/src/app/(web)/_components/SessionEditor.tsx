'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react';
import type { SessionEdit } from '@/lib/session-edit';
import { Avatar } from './Avatar';
import { IconAlert, IconClose, IconPencil } from './Icons';

type Editor = {
  /** Save an edit, returning an error message or null. Edits go one at a time, in order. */
  save: (edit: SessionEdit) => Promise<string | null>;
  /** The revision the next request should name. */
  revision: () => number;
  /** Take the revision another request saved (#117), as `save` does with its own. */
  saved: (revision: number) => void;
  /** Ask to reload: the session changed somewhere else. */
  markStale: () => void;
};

const EditorContext = createContext<Editor | null>(null);

/** The session page's editor, or null when the session can't be edited here. */
export const useSessionEditor = () => useContext(EditorContext);

/**
 * What the session page's edits share (#84): the latest revision, taken from
 * each answer so that quick edits in a row don't refuse each other, and the
 * prompt to reload when the session changed elsewhere. Without `editable` (a
 * session from a newer app) there is no editor, so no edit controls show.
 */
export function SessionEditor({ syncId, revision, editable, children }: { syncId: string; revision: number; editable: boolean; children: ReactNode }) {
  const router = useRouter();
  const latest = useRef(revision);
  const queue = useRef<Promise<unknown>>(Promise.resolve());
  const [stale, setStale] = useState(false);

  useEffect(() => {
    latest.current = Math.max(latest.current, revision);
  }, [revision]);

  const save = useCallback(
    (edit: SessionEdit) => {
      const run = async (): Promise<string | null> => {
        try {
          const res = await fetch(`/api/web/v1/sessions/${encodeURIComponent(syncId)}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ baseRevision: latest.current, edit }),
          });
          if (res.ok) {
            latest.current = ((await res.json()) as { revision: number }).revision;
            router.refresh();
            return null;
          }
          if (res.status === 409) {
            setStale(true);
            return 'Not saved: this session changed somewhere else. Reload the page to see the latest version.';
          }
          if (res.status === 404) return 'Not saved: this session was deleted.';
          if (res.status === 422) return 'Not saved: this session can’t be edited here.';
          return 'That did not work. Try again.';
        } catch {
          return 'The server could not be reached. Try again.';
        }
      };
      const result = queue.current.then(run);
      queue.current = result;
      return result;
    },
    [syncId, router],
  );
  const editor = useMemo(
    () => ({
      save,
      revision: () => latest.current,
      saved: (next: number) => {
        latest.current = Math.max(latest.current, next);
        router.refresh();
      },
      markStale: () => setStale(true),
    }),
    [save, router],
  );

  return (
    <EditorContext.Provider value={editable ? editor : null}>
      {stale && (
        <div className="edit-stale" role="alert">
          <IconAlert size={16} />
          <span>This session changed on a device or in another tab. Nothing was overwritten.</span>
          <button type="button" className="button" onClick={() => window.location.reload()}>
            Reload
          </button>
        </div>
      )}
      {children}
    </EditorContext.Provider>
  );
}

/**
 * `value`, or what was just saved until the page refreshes with a new `value`,
 * so a saved edit doesn't flicker back to the old text.
 */
export function useSaved<T>(value: T): [T, (next: T) => void] {
  const [saved, setSaved] = useState<{ base: T; next: T } | null>(null);
  return [saved && saved.base === value ? saved.next : value, (next) => setSaved({ base: value, next })];
}

type InlineProps = {
  value: string;
  label: string;
  maxLength?: number;
  className?: string;
  edit: (value: string) => SessionEdit;
  children: (value: string) => ReactNode;
};

/** A value with a pencil beside it, which turns it into a field: Enter saves, Esc cancels. */
function InlineText({ value, label, maxLength, className, edit, children }: InlineProps) {
  const editor = useSessionEditor();
  const [shown, setShown] = useSaved(value);
  const [draft, setDraft] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (!editor) return <>{children(value)}</>;
  if (draft === null) {
    return (
      <>
        {children(shown)}
        <button type="button" className="icon-button" aria-label={label} title={label} onClick={() => setDraft(shown)}>
          <IconPencil size={15} />
        </button>
      </>
    );
  }
  const cancel = () => {
    setDraft(null);
    setError(null);
  };
  const submit = async (event: FormEvent) => {
    event.preventDefault();
    const next = draft.trim();
    if (!next) return;
    if (next === shown) return cancel();
    setBusy(true);
    const message = await editor.save(edit(next));
    setBusy(false);
    setError(message);
    if (!message) {
      setShown(next);
      setDraft(null);
    }
  };
  return (
    <form className={['inline-edit', className].filter(Boolean).join(' ')} onSubmit={submit}>
      <input
        className="edit-input"
        aria-label={label}
        autoFocus
        value={draft}
        maxLength={maxLength}
        disabled={busy}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') cancel();
        }}
      />
      <button type="submit" className="button primary" disabled={busy || !draft.trim()}>
        Save
      </button>
      <button type="button" className="button ghost" disabled={busy} onClick={cancel}>
        Cancel
      </button>
      {error && (
        <span className="inline-error" role="alert">
          {error}
        </span>
      )}
    </form>
  );
}

/** The session's title (#84), capped by the same 100 characters as in the app. */
export function EditableTitle({ title }: { title: string }) {
  return (
    <div className="title-edit">
      <InlineText value={title} label="Rename session" maxLength={100} className="wide" edit={(next) => ({ kind: 'title', title: next })}>
        {(value) => <h1>{value}</h1>}
      </InlineText>
    </div>
  );
}

/** The session's speakers (#84); renaming one renames it on every line. */
export function EditableSpeakers({ speakers }: { speakers: { index: number; label: string }[] }) {
  return (
    <ul className="speakers" aria-label="Speakers">
      {speakers.map((s, i) => (
        <li key={s.index}>
          <InlineText value={s.label} label={`Rename speaker ${s.label}`} edit={(next) => ({ kind: 'speaker', index: s.index, label: next })}>
            {(value) => (
              <>
                <Avatar name={value} tone={i} size="sm" />
                {value}
              </>
            )}
          </InlineText>
        </li>
      ))}
    </ul>
  );
}

/** The session's tags (#84): each links to the library, with a button to remove it, and a field to add one. */
export function EditableTags({ tags }: { tags: string[] }) {
  const editor = useSessionEditor();
  const [shown, setShown] = useSaved(tags);
  const [draft, setDraft] = useState('');
  const [error, setError] = useState<string | null>(null);
  const chip = (tag: string) => (
    <Link className="chip" href={`/?tag=${encodeURIComponent(tag)}`}>
      {tag}
    </Link>
  );
  if (!editor) return tags.map((tag) => <span key={tag}>{chip(tag)}</span>);

  const write = async (next: string[]) => {
    const message = await editor.save({ kind: 'tags', tags: next });
    setError(message);
    if (!message) setShown(next);
    return !message;
  };
  const add = async () => {
    const tag = draft.trim();
    if (!tag) return;
    if (await write([...shown, tag])) setDraft('');
  };
  const onKey = (event: KeyboardEvent) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      void add();
    }
  };
  return (
    <span className="tag-edit">
      {shown.map((tag) => (
        <span key={tag} className="tag-chip">
          {chip(tag)}
          <button type="button" className="tag-remove" aria-label={`Remove tag ${tag}`} title={`Remove tag ${tag}`} onClick={() => void write(shown.filter((t) => t !== tag))}>
            <IconClose size={12} />
          </button>
        </span>
      ))}
      <input
        className="edit-input tag-input"
        aria-label="Add a tag"
        placeholder="Add a tag"
        maxLength={30}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={onKey}
      />
      {error && (
        <span className="inline-error" role="alert">
          {error}
        </span>
      )}
    </span>
  );
}
