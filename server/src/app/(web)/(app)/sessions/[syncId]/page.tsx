import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { formatBytes, formatDateTime, formatLength } from '@/lib/format';
import { KNOWN_SCHEMA, marksByLine, timestamp, type Message, type SessionView } from '@/lib/transcript-export';
import { pageLocale, requireIdentity } from '@/lib/web-identity';
import { readSession } from '@/lib/web-session';
import { Avatar } from '../../../_components/Avatar';
import { DownloadMenu, type Download } from '../../../_components/DownloadMenu';
import {
  IconAlert,
  IconCalendar,
  IconCheckCircle,
  IconChevronLeft,
  IconCircle,
  IconClock,
  IconGlobe,
} from '../../../_components/Icons';
import { Markdown } from '../../../_components/Markdown';
import { PlayerBar, PlayerProvider, SeekTime } from '../../../_components/Player';
import { Tabs, type Tab } from '../../../_components/Tabs';
import { Transcript } from '../../../_components/Transcript';

const SYNC_ID = /^[0-9A-Za-z-]{1,64}$/;

export async function generateMetadata({ params }: PageProps<'/sessions/[syncId]'>): Promise<Metadata> {
  const { syncId } = await params;
  const identity = SYNC_ID.test(syncId) ? await requireIdentity() : null;
  const session = identity ? await readSession(identity.owner, syncId) : null;
  return { title: session?.view.title ?? 'Session' };
}

/**
 * One session (#80): the transcript beside its summaries, action items,
 * marks and questions, with the recording in a player the transcript
 * follows. Read-only.
 */
export default async function SessionPage({ params }: PageProps<'/sessions/[syncId]'>) {
  const { owner } = await requireIdentity();
  const { syncId } = await params;
  if (!SYNC_ID.test(syncId)) notFound();
  const session = await readSession(owner, syncId);
  if (!session) notFound();
  const { view } = session;
  const { zone, locale } = await pageLocale();
  const when = (ms: number | null) => (ms !== null ? formatDateTime(ms, zone, locale) : null);

  const tones = Object.fromEntries(view.speakers.map((s, i) => [s.index, i]));
  const marks = Object.fromEntries(
    [...marksByLine(view)].map(([index, list]) => [index, list.map((m) => ({ ...m, stamp: timestamp(m.atMs) }))]),
  );
  const lines = view.lines.filter((l) => l.text.trim()).map((line) => ({ ...line, stamp: timestamp(line.startMs) }));
  const base = `/api/web/v1/sessions/${syncId}`;
  const downloads: Download[] = [
    ...(view.audio ? [{ href: `${base}/audio?download=1`, label: 'Recording', detail: `WAV · ${formatBytes(view.audio.size)}` }] : []),
    ...(view.cleanAudio
      ? [{ href: `${base}/audio?clean=1&download=1`, label: 'Cleaned recording', detail: `WAV · ${formatBytes(view.cleanAudio.size)}` }]
      : []),
    { href: `${base}/transcript/md`, label: 'Transcript', detail: 'Markdown' },
    { href: `${base}/transcript/srt`, label: 'Subtitles', detail: 'SRT' },
    { href: `${base}/transcript/vtt`, label: 'Subtitles', detail: 'WebVTT' },
  ];
  const tabs = panels(view, when, tones);

  return (
    <PlayerProvider src={view.audio ? `${base}/audio` : null}>
      <div className={view.audio ? 'page session with-player' : 'page session'}>
        <header className="session-head">
          <Link href="/" className="back">
            <IconChevronLeft size={16} /> All sessions
          </Link>
          <div className="title-row">
            <h1>{view.title}</h1>
            <DownloadMenu items={downloads} />
          </div>
          <div className="session-meta">
            {view.createdAt !== null && (
              <span className="meta-item">
                <IconCalendar size={15} />
                {when(view.createdAt)}
              </span>
            )}
            {view.durationMs ? (
              <span className="meta-item">
                <IconClock size={15} />
                {formatLength(view.durationMs)}
              </span>
            ) : null}
            {view.language && (
              <span className="meta-item">
                <IconGlobe size={15} />
                {languageName(view.language, locale)}
              </span>
            )}
            {view.tags.map((t) => (
              <Link key={t} className="chip" href={`/?tag=${encodeURIComponent(t)}`}>
                {t}
              </Link>
            ))}
          </div>
          {view.speakers.length > 0 && (
            <ul className="speakers" aria-label="Speakers">
              {view.speakers.map((s, i) => (
                <li key={s.index}>
                  <Avatar name={s.label} tone={i} size="sm" />
                  {s.label}
                </li>
              ))}
            </ul>
          )}
          {(view.schema ?? 0) > KNOWN_SCHEMA && (
            <p className="notice">
              <IconAlert size={16} /> This session comes from a newer version of the app; parts of it may not show here.
            </p>
          )}
        </header>

        <div className={tabs.length ? 'session-grid' : 'session-grid single'}>
          <section className="card transcript-card" aria-labelledby="transcript-heading">
            <div className="card-head">
              <h2 id="transcript-heading">Transcript</h2>
              <span className="muted">
                {lines.length} {lines.length === 1 ? 'line' : 'lines'}
                {view.audio && ' · tap a sentence to play from there'}
              </span>
            </div>
            <Transcript lines={lines} marks={marks} tones={tones} />
          </section>
          {tabs.length > 0 && (
            <aside className="card side-panel" aria-label="Notes">
              <Tabs tabs={tabs} label="Session notes" />
            </aside>
          )}
        </div>
      </div>
      <PlayerBar durationMs={view.durationMs} marks={view.marks.map((m) => ({ atMs: m.atMs, label: `${timestamp(m.atMs)} · ${m.note || 'Mark'}` }))} />
    </PlayerProvider>
  );
}

/** The language's name for a code like `en`, or the code. */
function languageName(code: string, locale: string): string {
  try {
    const name = new Intl.DisplayNames([locale], { type: 'language' }).of(code);
    return name ? name[0].toUpperCase() + name.slice(1) : code;
  } catch {
    return code;
  }
}

/** A `yyyy-mm-dd` due date as words, like `Fri 6 Nov`. */
function dueDate(value: string): string {
  const ms = Date.parse(`${value}T00:00:00Z`);
  if (Number.isNaN(ms)) return value;
  return new Intl.DateTimeFormat('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).format(ms);
}

function panels(view: SessionView, when: (ms: number | null) => string | null, tones: Record<number, number>): Tab[] {
  const tabs: Tab[] = [];
  const summaries = [...view.summaries].reverse();
  if (summaries.length) {
    tabs.push({
      id: 'summary',
      label: 'Summary',
      content: (
        <>
          <SummaryMeta message={summaries[0]} when={when} />
          <Markdown text={summaries[0].content} />
          {summaries.length > 1 && (
            <details className="earlier">
              <summary>Earlier summaries ({summaries.length - 1})</summary>
              {summaries.slice(1).map((m, i) => (
                <div key={i} className="earlier-item">
                  <SummaryMeta message={m} when={when} />
                  <Markdown text={m.content} />
                </div>
              ))}
            </details>
          )}
        </>
      ),
    });
  }
  if (view.actionItems.length) {
    const toneOf = new Map(view.speakers.map((s) => [s.label.toLowerCase(), tones[s.index] ?? 0]));
    const open = view.actionItems.filter((a) => !a.done).length;
    tabs.push({
      id: 'actions',
      label: 'Actions',
      count: open,
      content: (
        <ul className="actions">
          {view.actionItems.map((item, i) => (
            <li key={i} className={item.done ? 'action done' : 'action'}>
              <span className="action-check" aria-label={item.done ? 'Done' : 'Open'}>
                {item.done ? <IconCheckCircle size={20} /> : <IconCircle size={20} />}
              </span>
              <div className="action-body">
                <p className="action-text">{item.text}</p>
                <div className="action-meta">
                  {item.owner && (
                    <span className="meta-item">
                      <Avatar name={item.owner} tone={toneOf.get(item.owner.toLowerCase()) ?? 7} size="sm" />
                      {item.owner}
                    </span>
                  )}
                  {item.dueDate && (
                    <span className="meta-item">
                      <IconCalendar size={14} />
                      Due {dueDate(item.dueDate)}
                    </span>
                  )}
                  {item.atMs !== null && <SeekTime ms={item.atMs} label={timestamp(item.atMs)} />}
                </div>
              </div>
            </li>
          ))}
        </ul>
      ),
    });
  }
  if (view.marks.length) {
    tabs.push({
      id: 'marks',
      label: 'Marks',
      count: view.marks.length,
      content: (
        <ul className="marks">
          {view.marks.map((mark, i) => (
            <li key={i}>
              <div className="mark-head">
                <SeekTime ms={mark.atMs} label={timestamp(mark.atMs)} />
                <strong>{mark.note || 'Marked moment'}</strong>
              </div>
              {mark.expandedNote && (
                <p className={mark.expandedStale ? 'mark-note stale' : 'mark-note'}>
                  {mark.expandedNote}
                  {mark.expandedStale && <em> Written before the transcript changed.</em>}
                </p>
              )}
            </li>
          ))}
        </ul>
      ),
    });
  }
  const questions = pairs(view.questions);
  if (questions.length) {
    tabs.push({
      id: 'questions',
      label: 'Q&A',
      count: questions.length,
      content: (
        <ul className="qa-list">
          {questions.map((qa, i) => (
            <li key={i} className="qa">
              {qa.question && <p className="question">{qa.question.content}</p>}
              {qa.answer && <Markdown text={qa.answer.content} />}
            </li>
          ))}
        </ul>
      ),
    });
  }
  return tabs;
}

function SummaryMeta({ message, when }: { message: Message; when: (ms: number | null) => string | null }) {
  const text = [message.templateName, when(message.createdAt)].filter(Boolean).join(' · ');
  return text ? <p className="panel-meta">{text}</p> : null;
}

/** Q&A messages as question and answer pairs, in order. */
function pairs(messages: Message[]) {
  const out: { question?: Message; answer?: Message }[] = [];
  for (const m of messages) {
    if (m.role === 'USER' || !out.length || out.at(-1)!.answer) out.push({});
    out.at(-1)![m.role === 'USER' ? 'question' : 'answer'] = m;
  }
  return out;
}
