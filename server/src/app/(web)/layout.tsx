import Link from 'next/link';
import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { env } from '@/lib/env';
import { pageIdentity, pageLocale } from '@/lib/web-identity';
import { TimeZoneCookie } from './_components/TimeZoneCookie';
import './web.css';

export const metadata: Metadata = { title: 'ConvoScribe', robots: { index: false } };

/** The web app's frame (#80): who is signed in, and Sign out where this server can sign out. */
export default async function WebLayout({ children }: { children: ReactNode }) {
  const identity = await pageIdentity();
  const { zone, zoneKnown } = await pageLocale();
  const config = env();
  const canSignOut = config.AUTH_MODE === 'api-key' || config.WEB_SIGN_OUT_URL !== undefined;
  return (
    <html lang="en">
      <body>
        <TimeZoneCookie current={zoneKnown ? zone : undefined} />
        <header className="top">
          <Link href="/" className="brand">
            ConvoScribe
          </Link>
          {identity && (
            <div className="who">
              {config.AUTH_MODE === 'authz-proxy' && <span>{identity.owner}</span>}
              {canSignOut && (
                <form method="post" action="/api/web/v1/sign-out">
                  <button type="submit" className="link">
                    Sign out
                  </button>
                </form>
              )}
            </div>
          )}
        </header>
        <main>{children}</main>
      </body>
    </html>
  );
}
