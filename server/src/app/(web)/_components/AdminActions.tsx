'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useState, type FormEvent } from 'react';
import { ConfirmButton, send } from './TrashActions';

const GB = 1_000_000_000;

/** A user's quota, in GB as storage is sold; empty is unlimited (#82). */
export function QuotaEditor({ owner, quotaBytes }: { owner: string; quotaBytes: number | null }) {
  const router = useRouter();
  const [value, setValue] = useState(quotaBytes === null ? '' : String(quotaBytes / GB));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const save = async (event: FormEvent) => {
    event.preventDefault();
    setSaved(false);
    const text = value.trim();
    const gb = Number(text);
    if (text !== '' && (!Number.isFinite(gb) || gb < 0)) {
      setError('Enter a number of GB, or leave it empty for no limit.');
      return;
    }
    setBusy(true);
    const message = await send(
      '/api/web/v1/admin/users',
      'PUT',
      { owner, quotaBytes: text === '' ? null : Math.round(gb * GB) },
      { 400: 'That is too large a number of GB.' },
    );
    setBusy(false);
    setError(message);
    setSaved(!message);
    if (!message) router.refresh();
  };
  return (
    <form className="quota-form" onSubmit={save}>
      <input
        type="text"
        inputMode="decimal"
        value={value}
        placeholder="No limit"
        aria-label={`Quota of ${owner} in GB`}
        onChange={(event) => {
          setValue(event.target.value);
          setSaved(false);
        }}
      />
      <span className="unit">GB</span>
      <button type="submit" className="button" disabled={busy}>
        Save
      </button>
      {saved && <span className="saved">Saved</span>}
      {error && (
        <span className="inline-error" role="alert">
          {error}
        </span>
      )}
    </form>
  );
}

/** *Preview* and *Run* of clean-up (#82), and the page kept up to date while one is going. */
export function CleanupControls({ runRunning, previewRunning }: { runRunning: boolean; previewRunning: boolean }) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const going = runRunning || previewRunning;
  useEffect(() => {
    if (!going) return;
    const timer = setInterval(() => router.refresh(), 3000);
    return () => clearInterval(timer);
  }, [going, router]);
  const start = (dryRun: boolean) =>
    send('/api/web/v1/admin/cleanup', 'POST', { dryRun }, { 409: 'One is already going. Wait for it to finish.' });
  return (
    <div className="cleanup-actions">
      <button
        type="button"
        className="button"
        disabled={busy || previewRunning}
        onClick={async () => {
          setBusy(true);
          setError(await start(true));
          setBusy(false);
          router.refresh();
        }}
      >
        {previewRunning ? 'Preview going…' : 'Preview'}
      </button>
      {runRunning ? (
        <button type="button" className="button" disabled>
          Clean-up going…
        </button>
      ) : (
        <ConfirmButton
          label="Run clean-up"
          className="button"
          title="Run clean-up now?"
          confirm="Run clean-up"
          onConfirm={async () => {
            const message = await start(false);
            if (!message) router.refresh();
            return message;
          }}
        >
          <p>
            This removes for good what nothing uses any more, for every user: sessions past their time in the trash,
            and recordings, manifests and uploads unused for 7 days. Use <em>Preview</em> first to see how much.
          </p>
        </ConfirmButton>
      )}
      {error && (
        <span className="inline-error" role="alert">
          {error}
        </span>
      )}
    </div>
  );
}
