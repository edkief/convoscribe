import type { Metadata } from 'next';
import { formatBytes, formatDateTime } from '@/lib/format';
import { listOwners } from '@/lib/owners';
import { pageLocale, requireAdmin } from '@/lib/web-identity';
import { QuotaEditor } from '../../../_components/AdminActions';
import { AdminTabs } from '../../../_components/AdminTabs';

export const metadata: Metadata = { title: 'Users' };

/**
 * Who uses this server and how much they store (#82): counts, bytes and
 * times, never anything from another user's sessions.
 */
export default async function AdminUsersPage() {
  await requireAdmin();
  const { zone, locale } = await pageLocale();
  const users = await listOwners();

  return (
    <div className="page library">
      <header className="page-head">
        <div>
          <p className="eyebrow">Admin</p>
          <h1>Users</h1>
          <p className="page-intro">
            Everyone who has used this server. Stored bytes include the trash and unfinished uploads. Over quota, a
            user’s devices can’t upload new recordings, but edits and deletes still work.
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
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
