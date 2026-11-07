import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import { upstreamOf } from '@/lib/ai-proxy';
import { talkingPointsByDecision } from '@/lib/decision-client';
import { formatBytes, formatDateTime, formatLength } from '@/lib/format';
import { listTemplates, resolveTemplate } from '@/lib/summary-templates';
import { KNOWN_SCHEMA, marksByLine, spoken, timestamp, type Message, type SessionView } from '@/lib/transcript-export';
import { pageLocale, requireIdentity } from '@/lib/web-identity';
import { readSession } from '@/lib/web-session';
import { ActionList } from '../../../_components/ActionList';
import { DownloadMenu, type Download } from '../../../_components/DownloadMenu';
import { IconAlert, IconCalendar, IconChevronLeft, IconClock, IconGlobe } from '../../../_components/Icons';
import { MarkList } from '../../../_components/MarkList';
import { Markdown } from '../../../_components/Markdown';
import { PlayerBar, PlayerProvider } from '../../../_components/Player';
import { EditableSpeakers, EditableTags, EditableTitle, SessionEditor } from '../../../_components/SessionEditor';
import { QaPanel } from '../../../_components/QaPanel';
import { SummaryPanel } from '../../../_components/SummaryPanel';
import { TalkingPointList } from '../../../_components/TalkingPointList';
import { Tabs, type Tab } from '../../../_components/Tabs';
import { DeleteSession } from '../../../_components/TrashActions';
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
 * follows. *Delete* moves it to the trash (#81). Its title, tags, speakers,
 * lines and action items (#84) and talking points (#104) can be edited,
 * unless it comes from a newer app than this server knows.
 * A search result (#83) opens it on a line (`?line=`) or the summaries
 * (`?tab=summary`, with `&earlier=1` for an older one). When the server
 * offers a language model, the Summary tab can write the summary (#117)
 * and the Q&A tab can ask about the session (#118); the model can also name
 * the session, tick its talking points and write its marks' full notes (#119).
 */
export default async function SessionPage({ params, searchParams }: PageProps<'/sessions/[syncId]'>) {
  const { owner } = await requireIdentity();
  const { syncId } = await params;
  if (!SYNC_ID.test(syncId)) notFound();
  const session = await readSession(owner, syncId);
  if (!session) notFound();
  const { meta, view } = session;
  const { zone, locale } = await pageLocale();
  const when = (ms: number | null) => (ms !== null ? formatDateTime(ms, zone, locale) : null);

  const tones = Object.fromEntries(view.speakers.map((s, i) => [s.index, i]));
  const marks = Object.fromEntries(
    [...marksByLine(view)].map(([index, list]) => [index, list.map((m) => ({ ...m, stamp: timestamp(m.atMs) }))]),
  );
  const lines = spoken(view).map((line) => ({ ...line, stamp: timestamp(line.startMs) }));
  const { line, tab, earlier } = await searchParams;
  const target = typeof line === 'string' && /^\d{1,7}$/.test(line) && Number(line) < lines.length ? Number(line) : undefined;
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
  const newer = (view.schema ?? 0) > KNOWN_SCHEMA;
  // Summarising is a web edit, so only for a session this server can edit.
  const llm = !newer && !!upstreamOf('llm');
  const templates = llm ? await listTemplates(owner) : null;
  const summarise = templates && {
    syncId,
    templates: templates.map(({ key, name }) => ({ key, name })),
    selected: resolveTemplate(view.summaryTemplate, templates).key,
  };
  // The smaller actions (#119) read the transcript.
  const assist = llm && lines.length > 0 ? { syncId } : null;
  // Talking points can also be checked by a decision service alone, when switched on (#101, #136).
  const pointCheck = !newer && (llm || talkingPointsByDecision()) && lines.length > 0 ? { syncId } : null;
  const tabs = panels(view, when, tones, earlier === '1', !newer, summarise, { syncId, ask: llm }, assist, pointCheck);

  return (
    <SessionEditor syncId={syncId} revision={meta.revision} editable={!newer}>
      <PlayerProvider src={view.audio ? `${base}/audio` : null}>
        <div className={view.audio ? 'page session with-player' : 'page session'}>
          <header className="session-head">
            <Link href="/" className="back">
              <IconChevronLeft size={16} /> All sessions
            </Link>
            <div className="title-row">
              <EditableTitle
                title={view.title}
                syncId={syncId}
                suggest={llm && (lines.length > 0 || view.summaries.length > 0)}
                previousTitle={view.titleSource === 'auto' ? view.previousTitle : null}
              />
              <div className="title-actions">
                <DownloadMenu items={downloads} />
                <DeleteSession syncId={syncId} revision={meta.revision} title={view.title} />
              </div>
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
              <EditableTags tags={view.tags} />
            </div>
            {view.speakers.length > 0 && <EditableSpeakers speakers={view.speakers} />}
            {newer && (
              <p className="notice">
                <IconAlert size={16} /> This session comes from a newer version of the app; parts of it may not show here, and it can’t be edited here.
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
              <Transcript lines={lines} marks={marks} tones={tones} target={target} />
            </section>
            {tabs.length > 0 && (
              <aside className="card side-panel" aria-label="Notes">
                <Tabs tabs={tabs} label="Session notes" initial={typeof tab === 'string' ? tab : undefined} />
              </aside>
            )}
          </div>
        </div>
        <PlayerBar durationMs={view.durationMs} marks={view.marks.map((m) => ({ atMs: m.atMs, label: `${timestamp(m.atMs)} · ${m.note || 'Mark'}` }))} />
      </PlayerProvider>
    </SessionEditor>
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

function panels(
  view: SessionView,
  when: (ms: number | null) => string | null,
  tones: Record<number, number>,
  openEarlier: boolean,
  editable: boolean,
  summarise: { syncId: string; templates: { key: string; name: string }[]; selected: string } | null,
  qa: { syncId: string; ask: boolean },
  assist: { syncId: string } | null,
  pointCheck: { syncId: string } | null,
): Tab[] {
  const tabs: Tab[] = [];
  const summaries = [...view.summaries].reverse();
  const saved = summaries.length > 0 && (
    <>
      <SummaryMeta message={summaries[0]} when={when} />
      <Markdown text={summaries[0].content} />
      {summaries.length > 1 && (
        <details className="earlier" open={openEarlier}>
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
  );
  if (summaries.length || summarise) {
    tabs.push({
      id: 'summary',
      label: 'Summary',
      content: summarise ? (
        <SummaryPanel {...summarise} hasSummary={summaries.length > 0}>
          {saved}
        </SummaryPanel>
      ) : (
        saved
      ),
    });
  }
  if (view.actionItems.length) {
    const toneOf = Object.fromEntries(view.speakers.map((s) => [s.label.toLowerCase(), tones[s.index] ?? 0]));
    const open = view.actionItems.filter((a) => !a.done).length;
    tabs.push({
      id: 'actions',
      label: 'Actions',
      count: open,
      content: <ActionList items={view.actionItems} tones={toneOf} />,
    });
  }
  if (view.marks.length) {
    tabs.push({
      id: 'marks',
      label: 'Marks',
      count: view.marks.length,
      content: <MarkList marks={view.marks} write={assist} />,
    });
  }
  if (view.questions.length || qa.ask) {
    tabs.push({
      id: 'questions',
      label: 'Q&A',
      count: view.questions.filter((m) => m.role === 'USER').length || undefined,
      content: <QaPanel {...qa} questions={view.questions} />,
    });
  }
  // Next to the summary. Without points too where they can be added, unless
  // it would be the only tab, which would take the transcript's full width.
  if (view.talkingPoints.length || (editable && tabs.length)) {
    tabs.splice(tabs[0]?.id === 'summary' ? 1 : 0, 0, {
      id: 'points',
      label: 'Talking points',
      count: view.talkingPoints.length ? view.talkingPoints.filter((p) => !p.covered).length : undefined,
      content: <TalkingPointList points={view.talkingPoints} check={pointCheck} />,
    });
  }
  return tabs;
}

function SummaryMeta({ message, when }: { message: Message; when: (ms: number | null) => string | null }) {
  const text = [message.templateName, when(message.createdAt)].filter(Boolean).join(' · ');
  return text ? <p className="panel-meta">{text}</p> : null;
}
