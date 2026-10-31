'use client';

import { useRouter } from 'next/navigation';
import { useRef, useState, type ReactNode } from 'react';
import { IconRestore, IconTrash } from './Icons';

/** Run a state-changing request of the web API, returning an error message or null. */
async function send(url: string, method: string, body?: unknown, messages: Record<number, string> = {}): Promise<string | null> {
  try {
    const res = await fetch(url, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
    if (res.ok) return null;
    return messages[res.status] ?? 'That did not work. Try again.';
  } catch {
    return 'The server could not be reached. Try again.';
  }
}

type ConfirmProps = {
  label: ReactNode;
  className: string;
  title: string;
  children: ReactNode;
  confirm: string;
  /** Does the work; an error message keeps the dialog open to show it. */
  onConfirm: () => Promise<string | null>;
};

/** A button that asks first, in a modal dialog (#81). */
function ConfirmButton({ label, className, title, children, confirm, onConfirm }: ConfirmProps) {
  const dialog = useRef<HTMLDialogElement>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async () => {
    setBusy(true);
    setError(null);
    const message = await onConfirm();
    setBusy(false);
    if (message) setError(message);
    else dialog.current?.close();
  };
  return (
    <>
      <button type="button" className={className} onClick={() => dialog.current?.showModal()}>
        {label}
      </button>
      <dialog ref={dialog} className="dialog" onClose={() => setError(null)}>
        <h2>{title}</h2>
        <div className="dialog-body">{children}</div>
        {error && (
          <p className="dialog-error" role="alert">
            {error}
          </p>
        )}
        <div className="dialog-actions">
          <button type="button" className="button ghost" onClick={() => dialog.current?.close()} disabled={busy}>
            Cancel
          </button>
          <button type="button" className="button danger" onClick={run} disabled={busy}>
            {confirm}
          </button>
        </div>
      </dialog>
    </>
  );
}

/** The session page's *Delete*: to the trash, from the revision the page shows. */
export function DeleteSession({ syncId, revision, title }: { syncId: string; revision: number; title: string }) {
  const router = useRouter();
  return (
    <ConfirmButton
      label={
        <>
          <IconTrash size={16} /> Delete
        </>
      }
      className="button"
      title="Delete this session?"
      confirm="Delete"
      onConfirm={async () => {
        const error = await send(`/api/web/v1/sessions/${encodeURIComponent(syncId)}`, 'DELETE', { baseRevision: revision }, {
          409: 'This session changed on a device since this page loaded. Reload the page, then delete it again.',
          404: 'This session was already deleted.',
        });
        if (!error) {
          router.push('/');
          router.refresh();
        }
        return error;
      }}
    >
      <p>
        “{title}” is removed from this web app and from every device when it next syncs. It stays in the trash on
        this server for 30 days, where you can restore it.
      </p>
    </ConfirmButton>
  );
}

/** A trashed session's *Restore*. */
export function RestoreSession({ syncId }: { syncId: string }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  return (
    <span className="restore">
      {error && (
        <span className="inline-error" role="alert">
          {error}
        </span>
      )}
      <button
        type="button"
        className="button"
        disabled={busy}
        onClick={async () => {
          setBusy(true);
          const message = await send(`/api/web/v1/sessions/${encodeURIComponent(syncId)}/restore`, 'POST', undefined, {
            404: 'It can no longer be restored.',
          });
          setBusy(false);
          setError(message);
          router.refresh();
        }}
      >
        <IconRestore size={16} /> Restore
      </button>
    </span>
  );
}

/** *Empty trash*: every session in it is removed for good. */
export function EmptyTrash({ count }: { count: number }) {
  const router = useRouter();
  return (
    <ConfirmButton
      label={
        <>
          <IconTrash size={16} /> Empty trash
        </>
      }
      className="button"
      title="Empty the trash?"
      confirm="Empty trash"
      onConfirm={async () => {
        const error = await send('/api/web/v1/trash', 'DELETE');
        if (!error) router.refresh();
        return error;
      }}
    >
      <p>
        {count === 1 ? 'The session' : `The ${count} sessions`} in the trash will be removed for good, with their
        recordings, and can’t be restored.
      </p>
    </ConfirmButton>
  );
}
