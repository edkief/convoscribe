'use client';

import { useRouter } from 'next/navigation';
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { formatBytes, formatLength } from '@/lib/format';
import { describeError, type QueuedRecording, type QueueStatus } from './record/upload-queue';
import {
  announce,
  discard,
  discardInterrupted,
  interruptedRecordings,
  onChange,
  recover,
  retry,
  sweep,
  UPLOAD_LOCK,
  uploadRunner,
  type Interrupted,
} from './record/uploads';
import { IconAlert, IconArrowUp, IconSpinner } from './Icons';
import { ConfirmButton } from './TrashActions';

type Locks = { request(name: string, callback: () => Promise<void>): Promise<void> };

const errorText = (err: unknown) => (err instanceof Error ? err.message : String(err));
const recordings = (n: number) => `${n} recording${n === 1 ? '' : 's'}`;
/** When a recording started, in this browser's time zone. */
const when = (ms: number) => new Date(ms).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });

/**
 * Sends recordings made in the web app (#164) from any signed-in page: it
 * lives in the app frame, so the queue keeps going while you browse. One
 * tab sends (a Web Lock) and cleans up what a crash left behind; every tab
 * shows what is waiting, warns before the page is left with recordings
 * queued, and tries again at once when the browser comes back online or
 * the tab comes back into view.
 *
 * The status line opens a panel (#165) listing each recording on this
 * computer with its progress or last error, to retry or delete; recordings
 * a reload or a crash cut short, to recover or discard; and, when the
 * sign-in has expired, a way to sign in again. It opens by itself for
 * those last two.
 */
export function UploadQueue() {
  const router = useRouter();
  const [status, setStatus] = useState<QueueStatus | null>(null);
  const [interrupted, setInterrupted] = useState<Interrupted[]>([]);
  const [recovering, setRecovering] = useState<string | null>(null);
  const [recoverError, setRecoverError] = useState<string | null>(null);
  /** The user's open/closed choice, for the things that needed attention when they made it. */
  const [choice, setChoice] = useState<{ key: string; open: boolean } | null>(null);
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
    const run = async () => {
      await sweep().catch(() => {});
      await runner.run(() => {
        announce();
        router.refresh();
      });
    };
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
      setRecoverError(`It could not be recovered: ${errorText(err)}`);
    } finally {
      setRecovering(null);
      findInterrupted();
    }
  };

  if (!status || (items.length === 0 && interrupted.length === 0)) return null;
  const failed = items.filter((item) => item.failed);
  const waiting = items.length - failed.length;
  const signedOut = status.signedOut && waiting > 0;
  const active = status.active && items.find((item) => item.syncId === status.active!.syncId);
  const percent = (sent: number, size: number) => Math.floor((100 * sent) / Math.max(1, size));

  // What needs the user opens the panel, until they close it.
  const attention = [...interrupted.map((one) => one.draft.syncId), signedOut ? 'signed-out' : ''].filter(Boolean).join(',');
  const open = choice && choice.key === attention ? choice.open : attention !== '';

  let summary: ReactNode;
  if (interrupted.length > 0) {
    summary = (
      <>
        <IconAlert size={16} />
        <span>{interrupted.length === 1 ? 'A recording was interrupted' : `${interrupted.length} recordings were interrupted`}</span>
      </>
    );
  } else if (signedOut) {
    summary = (
      <>
        <IconAlert size={16} />
        <span>Sign in again to finish uploading</span>
      </>
    );
  } else if (waiting > 0 && active) {
    summary = (
      <>
        <IconSpinner size={16} className="spin" />
        <span>
          Uploading {recordings(waiting)}
          <span className="upload-pct"> · {percent(status.active!.sent, active.size)}%</span>
        </span>
      </>
    );
  } else if (waiting > 0) {
    summary = (
      <>
        <IconArrowUp size={16} />
        <span>{status.waiting ? `${recordings(waiting)} waiting for the network` : `${recordings(waiting)} to upload`}</span>
      </>
    );
  } else {
    summary = (
      <>
        <IconAlert size={16} />
        <span>{failed.length === 1 ? 'Upload failed' : `${failed.length} uploads failed`}</span>
      </>
    );
  }

  const stateOf = (item: QueuedRecording): ReactNode => {
    if (item.failed) return <span className="danger-text">Failed: {item.failed}</span>;
    if (active && item.syncId === active.syncId) return `Uploading · ${percent(status.active!.sent, item.size)}%`;
    if (signedOut) return 'Waits until you sign in again';
    if (item.lastError) return `Waiting to try again. ${describeError(item.lastError)}`;
    return 'Waiting to upload';
  };

  return (
    <details
      className="upload-status"
      open={open}
      onToggle={(e) => {
        const now = e.currentTarget.open;
        if (now !== open) setChoice({ key: attention, open: now });
      }}
    >
      <summary role="status" aria-live="polite" className={failed.length === items.length && interrupted.length === 0 ? 'upload-failed' : undefined}>
        {summary}
      </summary>
      <div className="upload-panel">
        <h2>Recordings on this computer</h2>

        {interrupted.map((one) => (
          <div key={one.draft.syncId} className="upload-item attention">
            <p>
              <strong>{when(one.draft.createdAt)}</strong>
            </p>
            <p>
              Interrupted by a reload or a closed tab. {formatLength(one.durationMs)} can be recovered, to within the last few
              seconds.
            </p>
            <div className="upload-actions">
              <button type="button" className="button primary" disabled={recovering !== null} onClick={() => recoverOne(one)}>
                {recovering === one.draft.syncId && <IconSpinner size={16} className="spin" />}
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
                    return errorText(err);
                  }
                }}
              >
                It was never uploaded, so it is deleted from this computer for good.
              </ConfirmButton>
            </div>
          </div>
        ))}
        {recoverError && <p className="danger-text">{recoverError}</p>}

        {signedOut && (
          <div className="upload-item attention">
            <p>Your sign-in has expired. {recordings(waiting)} will wait on this computer until you sign in again.</p>
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
                Sign in again
              </button>
            </div>
          </div>
        )}

        {items.length > 0 && (
          <ul className="upload-list">
            {items.map((item) => {
              const sending = active?.syncId === item.syncId;
              return (
                <li key={item.syncId} className="upload-item">
                  <p>
                    <strong>{when(item.createdAt)}</strong>
                    <span className="muted">
                      {' '}
                      · {formatLength(item.durationMs)} · {formatBytes(item.size)}
                      {item.asrModel ? '' : ' · no transcript'}
                    </span>
                  </p>
                  <p>{stateOf(item)}</p>
                  {sending && <progress max={item.size} value={Math.min(item.size, status.active!.sent)} aria-label="Upload progress" />}
                  <div className="upload-actions">
                    {item.failed && (
                      <button type="button" className="link-button" onClick={() => retry(item)}>
                        Retry
                      </button>
                    )}
                    {!sending && (
                      <ConfirmButton
                        label="Delete"
                        className="link-button"
                        title="Delete this recording?"
                        confirm="Delete"
                        onConfirm={async () => {
                          try {
                            await discard(item);
                            return null;
                          } catch (err) {
                            return errorText(err);
                          }
                        }}
                      >
                        It is not on the server yet, so it is deleted for good, with its transcript.
                      </ConfirmButton>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </div>
    </details>
  );
}
