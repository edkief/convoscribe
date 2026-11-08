import type { Metadata } from 'next';
import Link from 'next/link';
import { dayLabel, formatLength, formatTime } from '@/lib/format';
import { decodeCursor, encodeCursor } from '@/lib/session-index';
import { searchSessions, type LineMatch, type SearchMatch } from '@/lib/session-search';
import { timestamp } from '@/lib/transcript-export';
import { pageLocale, requireService } from '@/lib/web-identity';
import { Highlighted } from '../../_components/Highlighted';
import { IconClock, IconSearch } from '../../_components/Icons';

export const metadata: Metadata = { title: 'Search' };

/** Lines shown for a session before the rest fold away. */
const SHOWN_LINES = 3;

const one = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value) || undefined;

const sessionHref = (syncId: string, query = '') => `/sessions/${encodeURIComponent(syncId)}${query}`;

function LineLink({ syncId, line }: { syncId: string; line: LineMatch }) {
  return (
    <li>
      <Link href={sessionHref(syncId, `?line=${line.line}`)} className="hit">
        <span className="hit-where">
          {timestamp(line.startMs)}
          {line.speaker && ` · ${line.speaker}`}
        </span>
        <span className="hit-text">
          <Highlighted segments={line.text} />
        </span>
      </Link>
    </li>
  );
}

function Result({ session, day, time }: { session: SearchMatch; day: string; time: string }) {
  const shown = session.lines.slice(0, SHOWN_LINES);
  const folded = session.lines.slice(SHOWN_LINES);
  return (
    <li className="card result">
      <div className="result-head">
        <Link href={sessionHref(session.syncId)} className="result-title">
          {session.titleMatch ? <Highlighted segments={session.titleMatch} /> : session.title || 'Untitled session'}
        </Link>
        <span className="result-meta">
          {session.durationMs ? (
            <span className="meta-item">
              <IconClock size={14} />
              {formatLength(session.durationMs)}
            </span>
          ) : null}
          <span>
            {day} · {time}
          </span>
        </span>
      </div>
      <ul className="hits">
        {session.speakers.length > 0 && (
          <li>
            <Link href={sessionHref(session.syncId)} className="hit">
              <span className="hit-where">Speakers</span>
              <span className="hit-text">
                {session.speakers.map((speaker, i) => (
                  <span key={i}>
                    {i > 0 && ', '}
                    <Highlighted segments={speaker} />
                  </span>
                ))}
              </span>
            </Link>
          </li>
        )}
        {session.summaries.map((summary, i) => (
          <li key={i}>
            <Link href={sessionHref(session.syncId, summary.position > 0 ? '?tab=summary&earlier=1' : '?tab=summary')} className="hit">
              <span className="hit-where">{summary.position > 0 ? 'Earlier summary' : 'Summary'}</span>
              <span className="hit-text">
                <Highlighted segments={summary.text} />
              </span>
            </Link>
          </li>
        ))}
        {shown.map((line, i) => (
          <LineLink key={i} syncId={session.syncId} line={line} />
        ))}
      </ul>
      {folded.length > 0 && (
        <details className="more-hits">
          <summary>
            Show {folded.length} more {folded.length === 1 ? 'line' : 'lines'}
          </summary>
          <ul className="hits">
            {folded.map((line, i) => (
              <LineLink key={i} syncId={session.syncId} line={line} />
            ))}
          </ul>
          {session.moreLines > 0 && (
            <p className="muted">
              {session.moreLines} more {session.moreLines === 1 ? 'line' : 'lines'} match in{' '}
              <Link href={sessionHref(session.syncId)}>this session</Link>.
            </p>
          )}
        </details>
      )}
    </li>
  );
}

/**
 * Search (#83): the words of the signed-in user's transcripts, summaries,
 * titles and speakers, matched as in the app's search and grouped per
 * session, newest first. Each match links to where it was found. All from
 * Postgres.
 */
export default async function SearchPage({ searchParams }: PageProps<'/search'>) {
  const { owner } = await requireService('sync');
  const { zone, locale } = await pageLocale();
  const params = await searchParams;
  const q = one(params.q)?.trim() ?? '';
  const after = decodeCursor(one(params.after));
  const { sessions, next } = await searchSessions(owner, q, { after });
  const link = (cursor?: string) => {
    const query = new URLSearchParams({ q });
    if (cursor) query.set('after', cursor);
    return `/search?${query}`;
  };

  return (
    <div className="page search-page">
      <header className="page-head">
        <div>
          <p className="eyebrow">Search</p>
          <h1>{q ? `“${q}”` : 'Search your sessions'}</h1>
        </div>
      </header>
      <form className="search-box" method="get" action="/search" role="search">
        <IconSearch size={18} />
        <input type="search" name="q" defaultValue={q} placeholder="Words from a transcript, summary, title or speaker" aria-label="Search your sessions" maxLength={200} />
        <button type="submit" className="button">
          Search
        </button>
      </form>

      {!q ? (
        <p className="muted">Every word has to be in the same line, summary, title or speaker name. The start of a word is enough, and case and accents don&apos;t matter.</p>
      ) : sessions.length === 0 ? (
        <div className="empty-state">
          <span className="empty-icon">
            <IconSearch size={26} />
          </span>
          <h2>Nothing found</h2>
          <p>Every word has to be in the same line, summary, title or speaker name. Try fewer words.</p>
        </div>
      ) : (
        <ul className="results">
          {sessions.map((session) => (
            <Result
              key={session.syncId}
              session={session}
              day={dayLabel(session.createdAt, zone, locale)}
              time={formatTime(session.createdAt, zone, locale)}
            />
          ))}
        </ul>
      )}

      {(after || next) && (
        <nav className="pager" aria-label="Pages">
          {after ? (
            <Link href={link()} className="button ghost">
              Newest
            </Link>
          ) : (
            <span />
          )}
          {next && (
            <Link href={link(encodeCursor(next))} className="button">
              Older sessions
            </Link>
          )}
        </nav>
      )}
    </div>
  );
}
