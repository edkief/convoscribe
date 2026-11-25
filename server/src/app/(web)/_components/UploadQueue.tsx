'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState } from 'react';
import { formatBytes, formatLength } from '@/lib/format';
import type { QueueStatus } from './record/upload-queue';
import {
  announce,
  discardInterrupted,
  interruptedRecordings,
  onChange,
  recover,
  retry,
  UPLOAD_LOCK,
  uploadRunner,
  type Interrupted,
} from './record/uploads';
import { IconAlert, IconArrowUp, IconSpinner } from './Icons';
import { ConfirmButton } from './TrashActions';

type Locks = { request(name: string, callback: () => Promise<void>): Promise<void> };

/**
 * Sends recordings made in the web app (#164) from any signed-in page: it
 * lives in the app frame, so the queue keeps going while you browse. One
 * tab sends (a Web Lock); every tab shows what is waiting, warns before
 * the page is left with recordings queued, and tries again at once when the
 * browser comes back online.
 */
export function UploadQueue() {
  const router = useRouter();
  const [status, setStatus] = useState<QueueStatus | null>(null);
  const [interrupted, setInterrupted] = useState<Interrupted[]>([]);
  const [recovering, setRecovering] = useState<string | null>(null);
  const [recoverError, setRecoverError] = useState<string | null>(null);
  /** Set when the page is left on purpose, to sign in again: no warning then. */
  const leaving = useRef(false);

  // Recordings a reload or a crash cut short (#165), looked for again whenever the queue changes or the tab comes back.
  const findInterrupted = useCallback(() => {
    interruptedRecordings()
      .then(setInterrupted)
      .catch(() => {});
  }, []);
  useEffect(() => {
    findInterrupted();
    const off = onChange(findInterrupted);
    const visible = () => {
      if (document.visibilityState === 'visible') findInterrupted();
    };
    document.addEventListener('visibilitychange', visible);
    return () => {
      off();
      document.removeEventListener('visibilitychange', visible);
    };
  }, [findInterrupted]);

  useEffect(() => {
    const runner = uploadRunner();
    const unsubscribe = runner.subscribe(setStatus);
    const run = () =>
      runner.run(() => {
        announce();
        router.refresh();
      });
    const locks = (navigator as Navigator & { locks?: Locks }).locks;
    if (locks) locks.request(UPLOAD_LOCK, run).catch(() => {});
    else run().catch(() => {});
    // Back online, or back to this tab (perhaps signed in again in another one, #165): try at once.
    const kick = () => runner.kick();
    const visible = () => {
      if (document.visibilityState === 'visible') runner.kick();
    };
    window.addEventListener('online', kick);
    window.addEventListener('focus', kick);
    document.addEventListener('visibilitychange', visible);
    return () => {
      window.removeEventListener('online', kick);
      window.removeEventListener('focus', kick);
      document.removeEventListener('visibilitychange', visible);
      unsubscribe();
      runner.stop();
    };
  }, [router]);

  const items = status?.items ?? [];
  const queued = items.some((item) => !item.failed);
  useEffect(() => {
    if (!queued) return;
    const warn = (e: BeforeUnloadEvent) => {
      if (leaving.current) return;
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [queued]);

  const recoverOne = async ({ draft }: Interrupted) => {
    setRecovering(draft.syncId);
    setRecoverError(null);
    try {
      await recover(draft);
    } catch (err) {
      setRecoverError(`It could not be recovered: ${err instanceof Error ? err.message : String(err)}`);
    } finally {
      setRecovering(null);
      findInterrupted();
    }
  };

  if (!status || (items.length === 0 && interrupted.length === 0)) return null;
  const failed = items.filter((item) => item.failed);
  const waiting = items.length - failed.length;
  const active = status.active && items.find((item) => item.syncId === status.active!.syncId);
  const recordings = (n: number) => `${n} recording${n === 1 ? '' : 's'}`;

  return (
    <div className="upload-status" role="status" aria-live="polite">
      {interrupted.map((one) => (
        <div key={one.draft.syncId} className="upload-interrupted">
          <p>
            <IconAlert size={16} />
            <span>
              A recording from {when(one.draft.createdAt)} was interrupted; {formatLength(one.durationMs)} of it can be recovered.
            </span>
          </p>
          <div className="upload-actions">
            <button type="button" className="button" disabled={recovering !== null} onClick={() => recoverOne(one)}>
              {recovering === one.draft.syncId ? <IconSpinner size={16} className="spin" /> : null}
              Recover
            </button>
            <ConfirmButton
              label="Discard"
              className="link-button"
              title="Discard this recording?"
              confirm="Discard"
              onConfirm={async () => {
                try {
                  await discardInterrupted(one.draft);
                  findInterrupted();
                  return null;
                } catch (err) {
                  return err instanceof Error ? err.message : String(err);
                }
              }}
            >
              It was never uploaded, so it is deleted from this computer for good.
            </ConfirmButton>
          </div>
        </div>
      ))}
      {recoverError && <p className="upload-failed">{recoverError}</p>}
      {status.signedOut && waiting > 0 && (
        <div className="upload-interrupted">
          <p>
            <IconAlert size={16} />
            <span>Your sign-in has expired. {recordings(waiting)} will wait on this computer.</span>
          </p>
          <div className="upload-actions">
            <button
              type="button"
              className="button primary"
              onClick={() => {
                // Loading the page again goes through sign-in, the proxy's or the server's; the queue then goes on.
                leaving.current = true;
                window.location.reload();
              }}
            >
              Sign in again to finish uploading
            </button>
          </div>
        </div>
      )}
      {waiting > 0 &&
        !status.signedOut &&
        (active ? (
          <p title={`${formatBytes(status.active!.sent)} of ${formatBytes(active.size)}`}>
            <IconSpinner size={16} className="spin" />
            <span>
              Uploading {recordings(waiting)}
              <span className="upload-pct"> · {Math.floor((100 * status.active!.sent) / Math.max(1, active.size))}%</span>
            </span>
          </p>
        ) : (
          <p title={status.waiting ? `Last try: ${status.waiting}` : undefined}>
            <IconArrowUp size={16} />
            <span>{status.waiting ? `${recordings(waiting)} waiting for the network` : `${recordings(waiting)} to upload`}</span>
          </p>
        ))}
      {failed.map((item) => (
        <p key={item.syncId} className="upload-failed" title={item.failed ?? undefined}>
          <IconAlert size={16} />
          <span>Upload failed</span>
          <button type="button" className="link-button" onClick={() => retry(item)}>
            Retry
          </button>
        </p>
      ))}
    </div>
  );
}

/** When a recording started, in this browser's time zone. */
const when = (ms: number) => new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
