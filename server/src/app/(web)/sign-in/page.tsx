import { redirect } from 'next/navigation';
import { env } from '@/lib/env';
import { pageIdentity } from '@/lib/web-identity';

/**
 * Sign in (#80). In api-key mode with the server's SYNC_API_KEY; behind
 * authz-proxy the proxy signs browsers in, so this only says so.
 */
export default async function SignInPage({ searchParams }: PageProps<'/sign-in'>) {
  if (await pageIdentity()) redirect('/');
  if (env().AUTH_MODE === 'authz-proxy') {
    return (
      <section className="sign-in">
        <h1>Sign in</h1>
        <p>This server signs you in through authz-proxy. Open it at the address the proxy serves it on.</p>
      </section>
    );
  }
  const failed = (await searchParams).error !== undefined;
  return (
    <section className="sign-in">
      <h1>Sign in</h1>
      <form method="post" action="/api/web/v1/sign-in">
        {/* For password managers: this server has one user, and the key is its password. */}
        <input type="text" name="username" autoComplete="username" value="convoscribe" readOnly hidden />
        <label htmlFor="key">Sync key</label>
        <input id="key" name="key" type="password" autoComplete="current-password" required autoFocus />
        <p className="hint">The SYNC_API_KEY this server runs with, the same key your devices sync with.</p>
        {failed && (
          <p className="error" role="alert">
            That key is not this server&rsquo;s. Check it and try again.
          </p>
        )}
        <button type="submit">Sign in</button>
      </form>
    </section>
  );
}
