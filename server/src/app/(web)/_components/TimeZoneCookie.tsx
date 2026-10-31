'use client';

import { useRouter } from 'next/navigation';
import { useEffect } from 'react';

/**
 * Keeps the browser's time zone in a cookie, so the server writes dates and
 * reads date filters in it. Refreshes once when the zone was unknown or
 * changed, so the page is shown with the right dates.
 */
export function TimeZoneCookie({ current }: { current?: string }) {
  const router = useRouter();
  useEffect(() => {
    const zone = Intl.DateTimeFormat().resolvedOptions().timeZone;
    if (!zone || zone === current) return;
    document.cookie = `tz=${encodeURIComponent(zone)}; Path=/; Max-Age=31536000; SameSite=Lax`;
    router.refresh();
  }, [current, router]);
  return null;
}
