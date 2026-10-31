import type { Metadata } from 'next';
import { redirect } from 'next/navigation';
import { env } from '@/lib/env';
import { pageIdentity } from '@/lib/web-identity';
import { IconAlert, LogoMark } from '../_components/Icons';

export const metadata: Metadata = { title: 'Sign in' };

/**
 * Sign in (#80). In api-key mode with the server's SYNC_API_KEY; behind
 * authz-proxy the proxy signs browsers in, so this only says so.
 */
export default async function SignInPage({ searchParams }: PageProps<'/sign-in'>) {
  if (await pageIdentity()) redirect('/');
  const proxy = env().AUTH_MODE === 'authz-proxy';
  const failed = (await searchParams).error !== undefined;
  return (
    <main className="auth">
      <div className="auth-card">
        <LogoMark size={44} />
        <h1>Sign in to ConvoScribe</h1>
        <p className="auth-lead">Read, play and download the sessions your devices sync to this server.</p>
        {proxy ? (
          <p className="auth-note">
            This server signs you in through your organisation&rsquo;s sign-in page. Open it at the address the proxy
            serves it on.
          </p>
        ) : (
          <form method="post" action="/api/web/v1/sign-in" className="auth-form">
            {/* For password managers: this server has one user, and the key is its password. */}
            <input type="text" name="username" autoComplete="username" value="convoscribe" readOnly hidden />
            <label htmlFor="key">Sync key</label>
            <input
              id="key"
              name="key"
              type="password"
              autoComplete="current-password"
              placeholder="Paste the server's sync key"
              required
              autoFocus
              aria-invalid={failed || undefined}
              aria-describedby={failed ? 'key-error' : 'key-hint'}
            />
            {failed ? (
              <p className="auth-error" id="key-error" role="alert">
                <IconAlert size={16} /> That key is not this server&rsquo;s. Check it and try again.
              </p>
            ) : (
              <p className="auth-hint" id="key-hint">
                The <code>SYNC_API_KEY</code> this server runs with, the same key your devices sync with.
              </p>
            )}
            <button type="submit" className="button primary block">
              Sign in
            </button>
          </form>
        )}
      </div>
    </main>
  );
}
