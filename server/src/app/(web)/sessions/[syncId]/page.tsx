import Link from 'next/link';
import { notFound } from 'next/navigation';
import { formatBytes, formatDateTime } from '@/lib/format';
import { KNOWN_SCHEMA, marksByLine, timestamp, type Message } from '@/lib/transcript-export';
import { pageLocale, requireIdentity } from '@/lib/web-identity';
import { readSession } from '@/lib/web-session';
import { Markdown } from '../../_components/Markdown';
import { PlayerBar, PlayerProvider, SeekTime } from '../../_components/Player';
import { Transcript } from '../../_components/Transcript';

const SYNC_ID = /^[0-9A-Za-z-]{1,64}$/;

/**
 * One session (#80): its details, downloads, summaries, action items,
 * marks, questions and transcript, with its recording in a player the
 * transcript follows. Read-only.
 */
export default async function SessionPage({ params }: PageProps<'/sessions/[syncId]'>) {
  const { owner } = await requireIdentity();
  const { syncId } = await params;
  if (!SYNC_ID.test(syncId)) notFound();
  const session = await readSession(owner, syncId);
  if (!session) notFound();
  const { view } = session;
  const { zone, locale } = await pageLocale();
  const base = `/api/web/v1/sessions/${syncId}`;
  const when = (ms: number | null) => (ms !== null ? formatDateTime(ms, zone, locale) : null);
  const byLine = marksByLine(view);
  const marks = Object.fromEntries(
    [...byLine].map(([index, list]) => [index, list.map((m) => ({ ...m, stamp: timestamp(m.atMs) }))]),
  );
  const lines = view.lines.map((line) => ({ ...line, stamp: timestamp(line.startMs) }));
  const summaries = [...view.summaries].reverse();
  const questions = pairs(view.questions);

  return (
    <PlayerProvider src={view.audio ? `${base}/audio` : null}>
      <p className="back">
        <Link href="/">← All sessions</Link>
      </p>
      <h1>{view.title}</h1>
      <p className="details">
        {[
          when(view.createdAt),
          view.durationMs ? timestamp(view.durationMs) : null,
          view.language,
          view.speakers.length ? view.speakers.map((s) => s.label).join(', ') : null,
        ]
          .filter(Boolean)
          .join(' · ')}
      </p>
      {view.tags.length > 0 && (
        <p className="tags">
          {view.tags.map((t) => (
            <Link key={t} className="tag" href={`/?tag=${encodeURIComponent(t)}`}>
              {t}
            </Link>
          ))}
        </p>
      )}
      {(view.schema ?? 0) > KNOWN_SCHEMA && (
        <p className="notice">This session comes from a newer version of the app; parts of it may not show here.</p>
      )}

      <nav className="downloads" aria-label="Downloads">
        {view.audio && (
          <a href={`${base}/audio?download=1`} download>
            Recording ({formatBytes(view.audio.size)})
          </a>
        )}
        {view.cleanAudio && (
          <a href={`${base}/audio?clean=1&download=1`} download>
            Cleaned recording ({formatBytes(view.cleanAudio.size)})
          </a>
        )}
        <a href={`${base}/transcript/md`} download>
          Markdown
        </a>
        <a href={`${base}/transcript/srt`} download>
          SRT
        </a>
        <a href={`${base}/transcript/vtt`} download>
          VTT
        </a>
      </nav>
      <PlayerBar />

      {summaries.length > 0 && (
        <section>
          <h2>Summary</h2>
          <SummaryMeta message={summaries[0]} when={when} />
          <Markdown text={summaries[0].content} />
          {summaries.length > 1 && (
            <details>
              <summary>Earlier summaries ({summaries.length - 1})</summary>
              {summaries.slice(1).map((m, i) => (
                <div key={i} className="earlier">
                  <SummaryMeta message={m} when={when} />
                  <Markdown text={m.content} />
                </div>
              ))}
            </details>
          )}
        </section>
      )}

      {view.actionItems.length > 0 && (
        <section>
          <h2>Action items</h2>
          <ul className="actions">
            {view.actionItems.map((item, i) => (
              <li key={i} className={item.done ? 'done' : undefined}>
                <input type="checkbox" checked={item.done} disabled aria-label={item.done ? 'Done' : 'Not done'} />
                <span className="text">{item.text}</span>
                {(item.owner || item.dueDate) && (
                  <span className="meta">{[item.owner, item.dueDate && `due ${item.dueDate}`].filter(Boolean).join(' · ')}</span>
                )}
                {item.atMs !== null && <SeekTime ms={item.atMs} label={timestamp(item.atMs)} />}
              </li>
            ))}
          </ul>
        </section>
      )}

      {view.marks.length > 0 && (
        <section>
          <h2>Marks</h2>
          <ul className="marks">
            {view.marks.map((mark, i) => (
              <li key={i}>
                <SeekTime ms={mark.atMs} label={timestamp(mark.atMs)} />
                <span>{mark.note || 'Mark'}</span>
                {mark.expandedNote && <p className="mark-note">{mark.expandedNote}</p>}
              </li>
            ))}
          </ul>
        </section>
      )}

      {questions.length > 0 && (
        <section>
          <details className="questions">
            <summary>
              <h2>Questions ({questions.length})</h2>
            </summary>
            {questions.map((qa, i) => (
              <div key={i} className="qa">
                {qa.question && <p className="question">{qa.question.content}</p>}
                {qa.answer && <Markdown text={qa.answer.content} />}
              </div>
            ))}
          </details>
        </section>
      )}

      <section>
        <h2>Transcript</h2>
        <Transcript lines={lines} marks={marks} />
      </section>
    </PlayerProvider>
  );
}

function SummaryMeta({ message, when }: { message: Message; when: (ms: number | null) => string | null }) {
  const text = [message.templateName, when(message.createdAt)].filter(Boolean).join(' · ');
  return text ? <p className="meta">{text}</p> : null;
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
