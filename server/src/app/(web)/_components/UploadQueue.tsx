'use client';

import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';
import { formatBytes } from '@/lib/format';
import type { QueueStatus } from './record/upload-queue';
import { announce, retry, UPLOAD_LOCK, uploadRunner } from './record/uploads';
import { IconAlert, IconArrowUp, IconSpinner } from './Icons';

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
    const online = () => runner.kick();
    window.addEventListener('online', online);
    return () => {
      window.removeEventListener('online', online);
      unsubscribe();
      runner.stop();
    };
  }, [router]);

  const items = status?.items ?? [];
  const queued = items.some((item) => !item.failed);
  useEffect(() => {
    if (!queued) return;
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault();
      e.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [queued]);

  if (!status || items.length === 0) return null;
  const failed = items.filter((item) => item.failed);
  const waiting = items.length - failed.length;
  const active = status.active && items.find((item) => item.syncId === status.active!.syncId);
  const recordings = (n: number) => `${n} recording${n === 1 ? '' : 's'}`;

  return (
    <div className="upload-status" role="status" aria-live="polite">
      {waiting > 0 &&
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
