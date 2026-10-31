import type { Metadata } from 'next';
import { cleanupStatus, type CleanupKindStatus, type CleanupReport } from '@/lib/cleanup';
import { formatBytes, formatDateTime } from '@/lib/format';
import { serverStatus, type Reachability } from '@/lib/status';
import { pageLocale, requireAdmin } from '@/lib/web-identity';
import { CleanupControls } from '../../../_components/AdminActions';
import { AdminTabs } from '../../../_components/AdminTabs';

export const metadata: Metadata = { title: 'Status' };

const MODES = {
  on: 'Runs by itself once a day.',
  'dry-run': 'CLEANUP=dry-run: the daily run only reports. Run clean-up here still removes.',
  off: 'CLEANUP=off: no daily run. Run clean-up here still removes.',
};

function Reach({ name, state }: { name: string; state: Reachability }) {
  return (
    <div>
      <dt>{name}</dt>
      <dd>
        <span className={`reach ${state.ok ? 'ok' : 'down'}`}>{state.ok ? 'Reachable' : 'Unreachable'}</span>
        <span className="reach-time">{state.latencyMs} ms</span>
      </dd>
    </div>
  );
}

const describeReport = (r: CleanupReport) =>
  [
    `${r.trash.sessions} session${r.trash.sessions === 1 ? '' : 's'} from the trash`,
    `${r.recordings.count} recording${r.recordings.count === 1 ? '' : 's'} (${formatBytes(r.recordings.bytes)})`,
    `${r.manifests.count} manifest${r.manifests.count === 1 ? '' : 's'} (${formatBytes(r.manifests.bytes)})`,
    `${r.uploads.count} upload${r.uploads.count === 1 ? '' : 's'} (${formatBytes(r.uploads.bytes)})`,
  ].join(', ');

function CleanupKind(props: { title: string; verb: string; never: string; state: CleanupKindStatus; date: (ms: number) => string }) {
  const { title, verb, never, state, date } = props;
  const last = state.last;
  return (
    <div className="cleanup-kind">
      <h3>{title}</h3>
      {state.running && <p>Going now.</p>}
      {last ? (
        <p>
          {date(last.startedAt)}
          {last.trigger === 'scheduled' ? ' (daily)' : ''}
          {last.failed ? ': did not finish, see the server log. ' : ': '}
          {last.report && (
            <>
              {verb} {describeReport(last.report)}.
              {last.report.failures > 0 && ` ${last.report.failures} could not be removed, and the next run tries again.`}
            </>
          )}
        </p>
      ) : (
        !state.running && <p>{never}</p>
      )}
    </div>
  );
}

/**
 * Whether the server's storage can be reached, how much it holds, and
 * clean-up (#82). Renders with Postgres or S3 down: that is when it matters.
 */
export default async function AdminStatusPage() {
  await requireAdmin();
  const { zone, locale } = await pageLocale();
  const [status, cleanup] = await Promise.all([serverStatus(), cleanupStatus().catch(() => null)]);
  const date = (ms: number) => formatDateTime(ms, zone, locale);
  const storage = status.storage;

  return (
    <div className="page library">
      <header className="page-head">
        <div>
          <p className="eyebrow">Admin</p>
          <h1>Status</h1>
        </div>
      </header>
      <AdminTabs current="status" />
      <div className="admin-cards">
        <section className="card">
          <div className="card-head">
            <h2>Reachability</h2>
          </div>
          <div className="card-body">
            <dl className="facts">
              <Reach name="Postgres" state={status.postgres} />
              <Reach name="S3" state={status.s3} />
            </dl>
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <h2>Stored, all users</h2>
          </div>
          <div className="card-body">
            {storage ? (
              <>
                <dl className="facts">
                  <div>
                    <dt>Live</dt>
                    <dd>{formatBytes(storage.live)}</dd>
                  </div>
                  <div>
                    <dt>In the trash</dt>
                    <dd>{formatBytes(storage.trash)}</dd>
                  </div>
                  <div>
                    <dt>Unreferenced</dt>
                    <dd>{formatBytes(storage.unreferenced)}</dd>
                  </div>
                  <div>
                    <dt>In unfinished uploads</dt>
                    <dd>{formatBytes(storage.uploads)}</dd>
                  </div>
                  <div className="total">
                    <dt>Total</dt>
                    <dd>{formatBytes(storage.total)}</dd>
                  </div>
                </dl>
                <p className="card-note">
                  From Postgres. Earlier versions of manifests are not counted; a clean-up preview reports those old
                  enough to remove.
                </p>
              </>
            ) : (
              <p className="card-note">Not available while Postgres can’t be reached.</p>
            )}
          </div>
        </section>

        <section className="card">
          <div className="card-head">
            <h2>Clean-up</h2>
          </div>
          <div className="card-body">
            {cleanup ? (
              <>
                <p className="card-note" style={{ margin: '0 0 1rem' }}>
                  {MODES[cleanup.mode]}
                </p>
                <CleanupKind title="Last run" verb="Removed" never="Has not run yet." state={cleanup.run} date={date} />
                <CleanupKind title="Last preview" verb="Would remove" never="No preview yet." state={cleanup.preview} date={date} />
                <CleanupControls runRunning={cleanup.run.running} previewRunning={cleanup.preview.running} />
              </>
            ) : (
              <p className="card-note">Not available while Postgres can’t be reached.</p>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}
