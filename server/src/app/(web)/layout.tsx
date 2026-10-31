import '@fontsource-variable/inter';
import type { Metadata, Viewport } from 'next';
import type { ReactNode } from 'react';
import { pageLocale } from '@/lib/web-identity';
import { TimeZoneCookie } from './_components/TimeZoneCookie';
import './web.css';

export const metadata: Metadata = { title: { default: 'ConvoScribe', template: '%s · ConvoScribe' }, robots: { index: false } };

export const viewport: Viewport = {
  themeColor: [
    { media: '(prefers-color-scheme: light)', color: '#f6f7f9' },
    { media: '(prefers-color-scheme: dark)', color: '#0f1115' },
  ],
};

/** The web app's document (#80); the signed-in pages add the app frame in `(app)/layout.tsx`. */
export default async function RootLayout({ children }: { children: ReactNode }) {
  const { zone, zoneKnown } = await pageLocale();
  return (
    <html lang="en">
      <body>
        <TimeZoneCookie current={zoneKnown ? zone : undefined} />
        {children}
      </body>
    </html>
  );
}
