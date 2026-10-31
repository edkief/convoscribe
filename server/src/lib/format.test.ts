import { describe, expect, it } from 'vitest';
import { dayStart, formatBytes, formatDateTime, isoDate, preferredLocale, validZone } from './format';

describe('page formatting (#80)', () => {
  it('falls back to UTC and English for what the runtime does not know', () => {
    expect(validZone('Europe/Paris')).toBe('Europe/Paris');
    expect(validZone('Mars/Olympus')).toBe('UTC');
    expect(validZone(undefined)).toBe('UTC');
    expect(preferredLocale('fr-FR,fr;q=0.9,en;q=0.8')).toBe('fr-FR');
    expect(preferredLocale('*, !!')).toBe('en');
    expect(preferredLocale(null)).toBe('en');
  });

  it('writes dates in the browser’s zone', () => {
    const ms = Date.UTC(2026, 9, 31, 23, 30);
    expect(isoDate(ms, 'UTC')).toBe('2026-10-31');
    expect(isoDate(ms, 'Europe/Paris')).toBe('2026-11-01');
    expect(formatDateTime(ms, 'UTC', 'en-GB')).toBe('31 Oct 2026, 23:30');
  });

  it('finds when a day starts in a zone, across DST changes', () => {
    expect(dayStart('2026-10-31', 'UTC')).toBe(Date.UTC(2026, 9, 31));
    expect(dayStart('2026-10-31', 'UTC', 1)).toBe(Date.UTC(2026, 10, 1));
    // Paris is UTC+2 on 25 October 2026 and UTC+1 from 03:00 that day.
    expect(dayStart('2026-10-25', 'Europe/Paris')).toBe(Date.UTC(2026, 9, 24, 22));
    expect(dayStart('2026-10-26', 'Europe/Paris')).toBe(Date.UTC(2026, 9, 25, 23));
    expect(dayStart('2026-03-08', 'America/New_York')).toBe(Date.UTC(2026, 2, 8, 5));
    expect(dayStart('yesterday', 'UTC')).toBeUndefined();
    expect(dayStart(undefined, 'UTC')).toBeUndefined();
  });

  it('writes sizes in KB, MB and GB', () => {
    expect(formatBytes(999)).toBe('999 B');
    expect(formatBytes(1500)).toBe('1.5 KB');
    expect(formatBytes(115_000_000)).toBe('115 MB');
    expect(formatBytes(1_234_567_890)).toBe('1.2 GB');
  });
});
