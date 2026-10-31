/** Formatting for the web app's pages (#80): dates in the browser's zone, sizes, lengths. */

/** `zone` when the runtime knows it as an IANA time zone, else UTC. */
export function validZone(zone: string | undefined): string {
  if (!zone) return 'UTC';
  try {
    new Intl.DateTimeFormat('en', { timeZone: zone });
    return zone;
  } catch {
    return 'UTC';
  }
}

/** The first `Accept-Language` locale the runtime supports, else `en`. */
export function preferredLocale(acceptLanguage: string | null): string {
  for (const part of (acceptLanguage ?? '').split(',')) {
    const tag = part.split(';')[0].trim();
    if (!tag || tag === '*') continue;
    try {
      if (Intl.DateTimeFormat.supportedLocalesOf(tag).length) return tag;
    } catch {
      // Not a valid language tag.
    }
  }
  return 'en';
}

/** A date and time like the browser would write it, in `zone`. */
export function formatDateTime(ms: number, zone: string, locale = 'en'): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone: zone }).format(ms);
}

/** `yyyy-mm-dd` of `ms` in `zone`, for a date input. */
export function isoDate(ms: number, zone: string): string {
  return new Intl.DateTimeFormat('en-CA', { year: 'numeric', month: '2-digit', day: '2-digit', timeZone: zone }).format(ms);
}

/** How far `zone` is ahead of UTC at `utcMs`, in ms. */
function zoneOffset(utcMs: number, zone: string): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: zone,
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(utcMs);
  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value);
  const local = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'));
  return local - Math.floor(utcMs / 1000) * 1000;
}

/**
 * When the day `yyyy-mm-dd` (plus `addDays`) starts in `zone`, in ms since
 * epoch, or undefined for anything that is not such a date.
 */
export function dayStart(date: string | undefined, zone: string, addDays = 0): number | undefined {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date ?? '');
  if (!match) return undefined;
  const guess = Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3]) + addDays);
  if (Number.isNaN(guess)) return undefined;
  // Twice, so a day starting next to a DST change lands on the right offset.
  const first = guess - zoneOffset(guess, zone);
  return guess - zoneOffset(first, zone);
}

/** A byte count in KB, MB or GB (powers of 1000, as storage is sold). */
export function formatBytes(bytes: number): string {
  if (bytes < 1000) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = -1;
  do {
    value /= 1000;
    unit++;
  } while (value >= 1000 && unit < units.length - 1);
  return `${value < 10 ? value.toFixed(1) : Math.round(value)} ${units[unit]}`;
}
