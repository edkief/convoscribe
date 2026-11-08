import type { Metadata } from 'next';
import Link from 'next/link';
import { redirect } from 'next/navigation';
import { servicesOf } from '@/lib/access';
import { env } from '@/lib/env';
import { pageIdentity } from '@/lib/web-identity';
import { LogoMark } from '../_components/Icons';

export const metadata: Metadata = { title: 'No access' };

/**
 * Where a signed-in user not given sync lands (#145): the library shows
 * what was synced, so there is nothing for them here. Admins keep the
 * admin pages.
 */
export default async function NoAccessPage() {
  const identity = await pageIdentity();
  if (!identity) redirect('/sign-in');
  if ((await servicesOf(identity.owner)).has('sync')) redirect('/');
  const config = env();
  const proxy = config.AUTH_MODE === 'authz-proxy';
  const canSignOut = !proxy || config.WEB_SIGN_OUT_URL !== undefined;
  return (
    <main className="auth">
      <div className="auth-card">
        <LogoMark size={44} />
        <h1>No sessions here for you</h1>
        <p className="auth-lead">
          {proxy ? (
            <>
              Your account, <strong>{identity.owner}</strong>, isn&rsquo;t set up to sync sessions to this server.
            </>
          ) : (
            <>This server is set up not to sync sessions.</>
          )}{' '}
          Ask its admin to give you sync.
        </p>
        <div className="auth-actions">
          {identity.isAdmin && (
            <Link href="/admin/users" className="button primary block">
              Admin pages
            </Link>
          )}
          {canSignOut && (
            <form method="post" action="/api/web/v1/sign-out">
              <button type="submit" className="button block">
                Sign out
              </button>
            </form>
          )}
        </div>
      </div>
    </main>
  );
}
