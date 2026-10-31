import Link from 'next/link';
import { Suspense, type ReactNode } from 'react';
import { env } from '@/lib/env';
import { formatBytes, initials } from '@/lib/format';
import { countSessions, listTags, storageUsed } from '@/lib/session-index';
import { countTrash } from '@/lib/trash';
import { requireIdentity } from '@/lib/web-identity';
import { IconActivity, IconDatabase, IconKey, IconLogOut, IconTrash, LogoMark } from '../_components/Icons';
import { SidebarNav } from '../_components/SidebarNav';

/**
 * The frame of every signed-in page (#80): a sidebar with the library, its
 * tags, the trash (#81), the admin pages (#82), the storage used and who is
 * signed in; a top bar on narrow screens. It renders with Postgres down, so
 * the status page can say so.
 */
export default async function AppLayout({ children }: { children: ReactNode }) {
  const { owner, isAdmin } = await requireIdentity();
  const [tags, total, used, trash] = await Promise.all([
    listTags(owner),
    countSessions(owner),
    storageUsed(owner),
    countTrash(owner),
  ]).catch((err): [Awaited<ReturnType<typeof listTags>>, number, number, number] => {
    console.warn(`sidebar: ${err instanceof Error ? err.message : String(err)}`);
    return [[], 0, 0, 0];
  });
  const config = env();
  const proxy = config.AUTH_MODE === 'authz-proxy';
  const canSignOut = !proxy || config.WEB_SIGN_OUT_URL !== undefined;
  return (
    <div className="shell">
      <aside className="sidebar">
        <Link href="/" className="brand">
          <LogoMark />
          <span>ConvoScribe</span>
        </Link>
        <Suspense fallback={null}>
          <SidebarNav total={total} tags={tags} trash={trash} isAdmin={isAdmin} />
        </Suspense>
        <div className="side-foot">
          <div className="storage" title="Recordings stored on this server, including the trash">
            <IconDatabase />
            <span>
              <strong>{formatBytes(used)}</strong> of recordings
            </span>
          </div>
          <div className="account">
            {proxy ? (
              <span className="avatar" data-tone={0}>
                {initials(owner)}
              </span>
            ) : (
              <span className="avatar key">
                <IconKey size={16} />
              </span>
            )}
            <span className="who">
              <span className="name">{proxy ? owner : 'Sync key'}</span>
              <span className="sub">{proxy ? 'Signed in' : 'Signed in on this server'}</span>
            </span>
            {/* Narrow screens hide the sidebar's links; the trash and admin pages stay a tap away. */}
            {isAdmin && (
              <Link href="/admin/status" className="icon-button narrow-only" title="Admin" aria-label="Admin">
                <IconActivity />
              </Link>
            )}
            <Link href="/trash" className="icon-button narrow-only" title="Trash" aria-label="Trash">
              <IconTrash />
            </Link>
            {canSignOut && (
              <form method="post" action="/api/web/v1/sign-out">
                <button type="submit" className="icon-button" title="Sign out" aria-label="Sign out">
                  <IconLogOut />
                </button>
              </form>
            )}
          </div>
        </div>
      </aside>
      <div className="content">{children}</div>
    </div>
  );
}
