import type { Metadata } from 'next';
import { formatDateTime, formatLength } from '@/lib/format';
import { listTrash } from '@/lib/trash';
import { TRASH_DAYS } from '@/lib/documents';
import { pageLocale, requireIdentity } from '@/lib/web-identity';
import { IconClock, IconTrash } from '../../_components/Icons';
import { EmptyTrash, RestoreSession } from '../../_components/TrashActions';

export const metadata: Metadata = { title: 'Trash' };

/**
 * The trash (#81): sessions deleted on the web or on a device in the last
 * 30 days, each restorable to the library and to every device.
 */
export default async function TrashPage() {
  const { owner } = await requireIdentity();
  const { zone, locale } = await pageLocale();
  const items = await listTrash(owner);
  const date = (ms: number) => formatDateTime(ms, zone, locale);

  return (
    <div className="page library">
      <header className="page-head">
        <div>
          <p className="eyebrow">Library</p>
          <h1>Trash</h1>
          <p className="page-intro">
            Deleted sessions stay here for {TRASH_DAYS} days. Restoring one brings it back here and to your devices,
            with its recording.
          </p>
        </div>
        {items.length > 0 && <EmptyTrash count={items.length} />}
      </header>

      {items.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">
            <IconTrash size={26} />
          </span>
          <h2>The trash is empty</h2>
          <p>Sessions you delete, here or on a device, wait here for {TRASH_DAYS} days before they are removed.</p>
        </div>
      ) : (
        <ul className="rows">
          {items.map((item) => (
            <li key={item.syncId} className="row trash-row">
              <span className="row-main">
                <span className="row-title">{item.title || 'Untitled session'}</span>
                <span className="row-preview">
                  Deleted {date(item.trashedAt)} · removed for good {date(item.expiresAt)}
                </span>
              </span>
              <span className="meta-item row-length" title="Length">
                {item.durationMs ? (
                  <>
                    <IconClock size={14} />
                    {formatLength(item.durationMs)}
                  </>
                ) : null}
              </span>
              <RestoreSession syncId={item.syncId} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
