import type { Metadata } from 'next';
import Link from 'next/link';
import { dayLabel, dayStart, formatLength, formatTime, today } from '@/lib/format';
import { decodeCursor, encodeCursor, listSessions, listTags, type SessionListItem } from '@/lib/session-index';
import { pageLocale, requireIdentity } from '@/lib/web-identity';
import { AvatarStack } from '../_components/Avatar';
import { IconCheckCircle, IconClock, IconMic } from '../_components/Icons';

export const metadata: Metadata = { title: 'Sessions' };

const one = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value) || undefined;

/**
 * The library (#80): the signed-in user's sessions, newest first and
 * grouped by day, filtered by tag and date and paged, all from the session
 * index.
 */
export default async function LibraryPage({ searchParams }: PageProps<'/'>) {
  const { owner } = await requireIdentity();
  const { zone, locale } = await pageLocale();
  const params = await searchParams;
  const tag = one(params.tag);
  const from = one(params.from);
  const to = one(params.to);
  const after = decodeCursor(one(params.after));
  const dated = Boolean(from || to);
  const [{ sessions, next }, tags] = await Promise.all([
    listSessions(owner, { tag, from: dayStart(from, zone), to: dayStart(to, zone, 1), after }),
    listTags(owner),
  ]);
  const link = (extra: Record<string, string | undefined>) => {
    const query = new URLSearchParams();
    for (const [name, value] of Object.entries({ tag, from, to, ...extra })) if (value) query.set(name, value);
    const text = query.toString();
    return text ? `/?${text}` : '/';
  };
  const days: { label: string; sessions: SessionListItem[] }[] = [];
  for (const s of sessions) {
    const label = dayLabel(s.createdAt, zone, locale);
    if (days.at(-1)?.label === label) days.at(-1)!.sessions.push(s);
    else days.push({ label, sessions: [s] });
  }

  return (
    <div className="page library">
      <header className="page-head">
        <div>
          <p className="eyebrow">{tag ? 'Tag' : 'Library'}</p>
          <h1>{tag ?? 'All sessions'}</h1>
        </div>
        <form className="toolbar" method="get" action="/">
          <label className="field narrow-only">
            <span>Tag</span>
            <select name="tag" defaultValue={tag ?? ''}>
              <option value="">All tags</option>
              {tags.map(({ tag: t }) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>
          </label>
          <label className="field">
            <span>From</span>
            <input type="date" name="from" defaultValue={from} max={today(zone)} />
          </label>
          <label className="field">
            <span>To</span>
            <input type="date" name="to" defaultValue={to} max={today(zone)} />
          </label>
          <button type="submit" className="button">
            Apply
          </button>
          {(dated || tag) && (
            <Link href="/" className="button ghost">
              Clear
            </Link>
          )}
        </form>
      </header>

      {sessions.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">
            <IconMic size={26} />
          </span>
          {tag || dated || after ? (
            <>
              <h2>No sessions match</h2>
              <p>Try another tag or a wider date range.</p>
              <Link href="/" className="button">
                Show all sessions
              </Link>
            </>
          ) : (
            <>
              <h2>No sessions yet</h2>
              <p>
                Turn on sync in the app (<em>Settings → Sync</em>). Your sessions appear here once a device has
                uploaded them.
              </p>
            </>
          )}
        </div>
      ) : (
        <div className="day-list">
          {days.map((day) => (
            <section key={day.label} className="day">
              <h2 className="day-label">{day.label}</h2>
              <ul className="rows">
                {day.sessions.map((s) => (
                  <li key={s.syncId}>
                    <Link href={`/sessions/${encodeURIComponent(s.syncId)}`} className="row">
                      <span className="row-main">
                        <span className="row-title">{s.title || 'Untitled session'}</span>
                        {s.preview && <span className="row-preview">{s.preview}</span>}
                        {s.tags.length > 0 && (
                          <span className="chips">
                            {s.tags.map((t) => (
                              <span key={t} className="chip">
                                {t}
                              </span>
                            ))}
                          </span>
                        )}
                      </span>
                      <span className="row-meta">
                        <span className="row-speakers">
                          <AvatarStack names={s.speakers} />
                        </span>
                        <span className="row-actions">
                          {s.openActions > 0 && (
                            <span className="pill" title="Open action items">
                              <IconCheckCircle size={14} />
                              {s.openActions} open
                            </span>
                          )}
                        </span>
                        <span className="meta-item row-length" title="Length">
                          {s.durationMs ? (
                            <>
                              <IconClock size={14} />
                              {formatLength(s.durationMs)}
                            </>
                          ) : null}
                        </span>
                        <span className="time-of-day" title="Recorded">
                          {formatTime(s.createdAt, zone, locale)}
                        </span>
                      </span>
                    </Link>
                  </li>
                ))}
              </ul>
            </section>
          ))}
        </div>
      )}

      {(after || next) && (
        <nav className="pager" aria-label="Pages">
          {after ? (
            <Link href={link({ after: undefined })} className="button ghost">
              Newest
            </Link>
          ) : (
            <span />
          )}
          {next && (
            <Link href={link({ after: encodeCursor(next) })} className="button">
              Older sessions
            </Link>
          )}
        </nav>
      )}
    </div>
  );
}
