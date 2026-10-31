import Link from 'next/link';
import { dayStart, formatBytes, formatDateTime } from '@/lib/format';
import { decodeCursor, encodeCursor, listSessions, listTags, storageUsed } from '@/lib/session-index';
import { timestamp } from '@/lib/transcript-export';
import { pageLocale, requireIdentity } from '@/lib/web-identity';

const one = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value) || undefined;

/**
 * The library (#80): the signed-in user's sessions, newest first, filtered
 * by tag and date and paged, all from the session index.
 */
export default async function LibraryPage({ searchParams }: PageProps<'/'>) {
  const { owner } = await requireIdentity();
  const { zone, locale } = await pageLocale();
  const params = await searchParams;
  const tag = one(params.tag);
  const from = one(params.from);
  const to = one(params.to);
  const after = decodeCursor(one(params.after));
  const filtered = Boolean(tag || from || to);
  const [{ sessions, next }, tags, used] = await Promise.all([
    listSessions(owner, { tag, from: dayStart(from, zone), to: dayStart(to, zone, 1), after }),
    listTags(owner),
    storageUsed(owner),
  ]);
  const link = (extra: Record<string, string>) => {
    const query = new URLSearchParams();
    for (const [name, value] of Object.entries({ tag, from, to, ...extra })) if (value) query.set(name, value);
    const text = query.toString();
    return text ? `/?${text}` : '/';
  };
  return (
    <>
      <div className="library-head">
        <h1>Sessions</h1>
        <p className="storage">Recordings stored: {formatBytes(used)}</p>
      </div>
      <form className="filters" method="get" action="/">
        <label>
          Tag
          <select name="tag" defaultValue={tag ?? ''}>
            <option value="">All tags</option>
            {tags.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        </label>
        <label>
          From
          <input type="date" name="from" defaultValue={from} />
        </label>
        <label>
          To
          <input type="date" name="to" defaultValue={to} />
        </label>
        <button type="submit">Filter</button>
        {filtered && <Link href="/">Clear</Link>}
      </form>
      {sessions.length === 0 ? (
        <p className="empty">
          {filtered || after
            ? 'No sessions match these filters.'
            : 'No sessions yet. Turn on Sync in the app (Settings → Sync) and your sessions appear here.'}
        </p>
      ) : (
        <ul className="sessions">
          {sessions.map((s) => (
            <li key={s.syncId}>
              <Link href={`/sessions/${encodeURIComponent(s.syncId)}`}>
                <span className="title">{s.title || 'Untitled session'}</span>
                <span className="details">
                  {[
                    formatDateTime(s.createdAt, zone, locale),
                    s.durationMs ? timestamp(s.durationMs) : null,
                    s.speakerCount ? `${s.speakerCount} speaker${s.speakerCount === 1 ? '' : 's'}` : null,
                  ]
                    .filter(Boolean)
                    .join(' · ')}
                </span>
                {s.tags.length > 0 && (
                  <span className="tags">
                    {s.tags.map((t) => (
                      <span key={t} className="tag">
                        {t}
                      </span>
                    ))}
                  </span>
                )}
              </Link>
            </li>
          ))}
        </ul>
      )}
      <nav className="pages">
        {after && <Link href={link({})}>Newest</Link>}
        {next && <Link href={link({ after: encodeCursor(next) })}>Older sessions →</Link>}
      </nav>
    </>
  );
}
