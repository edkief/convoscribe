import type { Metadata } from 'next';
import { defaultServices } from '@/lib/access';
import { upstreamOf } from '@/lib/ai-proxy';
import { formatBytes, formatDateTime } from '@/lib/format';
import { listOwners } from '@/lib/owners';
import { SERVICE_LABELS } from '@/lib/service-labels';
import { pageLocale, requireAdmin } from '@/lib/web-identity';
import { QuotaEditor, ServicesEditor } from '../../../_components/AdminActions';
import { AdminTabs } from '../../../_components/AdminTabs';

export const metadata: Metadata = { title: 'Users' };

/**
 * Who uses this server, how much they store (#82) and what they may use
 * (#145): counts, bytes and times, never anything from another user's
 * sessions.
 */
export default async function AdminUsersPage() {
  await requireAdmin();
  const { zone, locale } = await pageLocale();
  const users = await listOwners();
  const defaults = defaultServices();
  const offered = { sync: true, llm: !!upstreamOf('llm'), asr: !!upstreamOf('asr'), decision: !!upstreamOf('decision') };
  const defaultText = defaults.length ? defaults.map((s) => SERVICE_LABELS[s].toLowerCase()).join(', ') : 'nothing';

  return (
    <div className="page library">
      <header className="page-head">
        <div>
          <p className="eyebrow">Admin</p>
          <h1>Users</h1>
          <p className="page-intro">
            Everyone who has used this server. Stored bytes include the trash and unfinished uploads. Over quota, a
            user’s devices can’t upload new recordings, but edits and deletes still work. A user may use only the
            services lit up, and a tap switches one; without sync, their devices can’t sync and the web app shows them
            nothing. Until you choose, a user has the default ({defaultText}; <code>DEFAULT_USER_SERVICES</code>).
            Dashed services are not set up on this server.
          </p>
        </div>
      </header>
      <AdminTabs current="users" />
      <div className="rows table-wrap">
        <table className="admin-table">
          <thead>
            <tr>
              <th scope="col">User</th>
              <th scope="col" className="num">
                Sessions
              </th>
              <th scope="col" className="num">
                In trash
              </th>
              <th scope="col" className="num">
                Stored
              </th>
              <th scope="col">Last activity</th>
              <th scope="col">Quota</th>
              <th scope="col">Services</th>
            </tr>
          </thead>
          <tbody>
            {users.map((user) => (
              <tr key={user.owner}>
                <td className="owner">{user.owner}</td>
                <td className="num">{user.sessions}</td>
                <td className="num">{user.trashed}</td>
                <td className="num">
                  {formatBytes(user.bytes)}
                  {user.quotaBytes !== null && user.bytes >= user.quotaBytes && <span className="over-quota">Quota reached</span>}
                </td>
                <td>{formatDateTime(user.lastSeenAt, zone, locale)}</td>
                <td>
                  <QuotaEditor owner={user.owner} quotaBytes={user.quotaBytes} />
                </td>
                <td>
                  <ServicesEditor
                    key={user.services?.join(',') ?? 'default'}
                    owner={user.owner} services={user.services} defaults={defaults} offered={offered} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
