import Link from 'next/link';
import { Suspense, type ReactNode } from 'react';
import { env } from '@/lib/env';
import { formatBytes, initials } from '@/lib/format';
import { countSessions, listTags, storageUsed } from '@/lib/session-index';
import { requireIdentity } from '@/lib/web-identity';
import { IconDatabase, IconKey, IconLogOut, LogoMark } from '../_components/Icons';
import { SidebarNav } from '../_components/SidebarNav';

/**
 * The frame of every signed-in page (#80): a sidebar with the library, its
 * tags, the storage used and who is signed in; a top bar on narrow screens.
 */
export default async function AppLayout({ children }: { children: ReactNode }) {
  const { owner } = await requireIdentity();
  const [tags, total, used] = await Promise.all([listTags(owner), countSessions(owner), storageUsed(owner)]);
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
          <SidebarNav total={total} tags={tags} />
        </Suspense>
        <div className="side-foot">
          <div className="storage" title="Recordings stored on this server">
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
