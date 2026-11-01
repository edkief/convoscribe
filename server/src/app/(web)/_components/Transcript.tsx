'use client';

import { memo, useCallback, useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import type { Mark, TranscriptLine } from '@/lib/transcript-export';
import { Avatar } from './Avatar';
import { IconFlag, IconLocate, IconPencil } from './Icons';
import { usePlayer } from './Player';
import { useSessionEditor } from './SessionEditor';

type Line = TranscriptLine & { stamp: string };
type StampedMark = Mark & { stamp: string };

type Props = {
  lines: Line[];
  /** Marks by the index of the line they go above; `-1` when there are no lines. */
  marks: Record<number, StampedMark[]>;
  /** Each speaker index's position in the session, for its colour. */
  tones: Record<number, number>;
  /** The line a search result opened on (#83). */
  target?: number;
};

/** Consecutive lines of one speaker, broken by a mark. */
type Turn = { kind: 'turn'; first: number; last: number; speaker: string | null; tone: number };
type Item = Turn | { kind: 'mark'; mark: StampedMark };

function items(lines: Line[], marks: Props['marks'], tones: Props['tones']): Item[] {
  const out: Item[] = (marks[-1] ?? []).map((mark) => ({ kind: 'mark', mark }));
  lines.forEach((line, i) => {
    for (const mark of marks[i] ?? []) out.push({ kind: 'mark', mark });
    const last = out.at(-1);
    if (last?.kind === 'turn' && lines[last.last].speakerIndex === line.speakerIndex) last.last = i;
    else out.push({ kind: 'turn', first: i, last: i, speaker: line.speaker, tone: tones[line.speakerIndex ?? -1] ?? 0 });
  });
  return out;
}

/** Index of the line being spoken at `ms`: the last one that had started, or -1. */
function lineAt(lines: { startMs: number }[], ms: number): number {
  let lo = 0;
  let hi = lines.length - 1;
  let found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (lines[mid].startMs <= ms) {
      found = mid;
      lo = mid + 1;
    } else hi = mid - 1;
  }
  return found;
}

const NOTHING_SAVED: Record<number, string> = {};

const lineElement = (index: number) => document.querySelector<HTMLElement>(`[data-line="${index}"]`);

const MarkRow = ({ mark }: { mark: StampedMark }) => {
  const player = usePlayer();
  return (
    <div className="t-mark">
      <IconFlag size={15} />
      {player ? (
        <button type="button" className="t-mark-time" onClick={() => player.seek(mark.atMs)}>
          {mark.stamp}
        </button>
      ) : (
        <span className="t-mark-time">{mark.stamp}</span>
      )}
      <span className="t-mark-note">{mark.note || 'Marked moment'}</span>
    </div>
  );
};

/** A line's text as a field (#84): Ctrl/Cmd+Enter saves, Esc cancels. */
function LineEditor({ text, onSave, onClose }: { text: string; onSave: (text: string) => Promise<string | null>; onClose: () => void }) {
  const [draft, setDraft] = useState(text);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const save = async () => {
    if (!draft.trim()) return;
    setBusy(true);
    const message = await onSave(draft);
    setBusy(false);
    setError(message);
    if (!message) onClose();
  };
  return (
    <span className="line-edit">
      <textarea
        className="edit-input"
        aria-label="Line text"
        autoFocus
        rows={Math.min(8, Math.ceil(text.length / 70) + 1)}
        value={draft}
        disabled={busy}
        onChange={(event) => setDraft(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Escape') onClose();
          else if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
            event.preventDefault();
            void save();
          }
        }}
      />
      <span className="line-edit-buttons">
        <button type="button" className="button primary" disabled={busy || !draft.trim()} onClick={() => void save()}>
          Save
        </button>
        <button type="button" className="button ghost" disabled={busy} onClick={onClose}>
          Cancel
        </button>
        {error && (
          <span className="inline-error" role="alert">
            {error}
          </span>
        )}
      </span>
    </span>
  );
}

const TurnView = memo(function TurnView({
  turn,
  lines,
  active,
  target,
  onSeek,
  saved,
  open,
  onOpen,
  onClose,
  onSave,
}: {
  turn: Turn;
  lines: Line[];
  /** The playing line, when it is in this turn; else -1. */
  active: number;
  /** The line opened from search, when it is in this turn; else -1. */
  target: number;
  onSeek?: (index: number) => void;
  /** Text just saved, by line, until the page refreshes. */
  saved: Record<number, string>;
  /** The line being edited, when it is in this turn; else -1. */
  open: number;
  /** In edit mode: tapping a line opens it for editing instead of seeking. */
  onOpen?: (index: number) => void;
  onClose: () => void;
  onSave: (index: number, text: string) => Promise<string | null>;
}) {
  const own = lines.slice(turn.first, turn.last + 1);
  const tap = onOpen ?? onSeek;
  const key = (index: number) => (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      tap?.(index);
    }
  };
  return (
    <div className={turn.speaker ? 'turn' : 'turn anonymous'}>
      {turn.speaker ? <Avatar name={turn.speaker} tone={turn.tone} /> : <span className="avatar-gap" />}
      <div className="turn-body">
        <div className="turn-head">
          {turn.speaker && (
            <span className="turn-speaker" data-tone={turn.tone}>
              {turn.speaker}
            </span>
          )}
          <span className="turn-time">{own[0].stamp}</span>
        </div>
        <p className="turn-text">
          {own.map((line, j) => {
            const index = turn.first + j;
            const className = ['seg', index === active && 'active', index === target && 'target'].filter(Boolean).join(' ');
            const text = saved[index] ?? line.text;
            if (index === open) {
              return <LineEditor key={index} text={text} onSave={(next) => onSave(index, next)} onClose={onClose} />;
            }
            return tap ? (
              <span
                key={index}
                data-line={index}
                className={className}
                role="button"
                tabIndex={0}
                title={onOpen ? 'Edit this line' : `Play from ${line.stamp}`}
                onClick={() => tap(index)}
                onKeyDown={key(index)}
              >
                {text}{' '}
              </span>
            ) : (
              <span key={index} data-line={index} className={className}>
                {text}{' '}
              </span>
            );
          })}
        </p>
      </div>
    </div>
  );
});

/**
 * The transcript as speaker turns, each mark between them where it was
 * made (#80). With a recording, tapping a sentence plays from it; the one
 * being played is highlighted and kept in view until the reader scrolls
 * away, when a button brings it back. Opened from a search result (#83), it
 * starts at that line, marked, with the player there but not playing.
 * *Edit text* (#84) makes tapping a line open it for editing instead, and
 * stops the transcript following playback meanwhile.
 */
export function Transcript({ lines, marks, tones, target }: Props) {
  const player = usePlayer();
  const editor = useSessionEditor();
  const [active, setActive] = useState(-1);
  const [follow, setFollow] = useState(true);
  const [editing, setEditing] = useState(false);
  const [open, setOpen] = useState(-1);
  const [savedText, setSavedText] = useState<{ base: Line[]; text: Record<number, string> }>({ base: lines, text: {} });
  const saved = savedText.base === lines ? savedText.text : NOTHING_SAVED;
  const list = useMemo(() => items(lines, marks, tones), [lines, marks, tones]);

  useEffect(() => {
    if (!player) return;
    return player.subscribe((ms, playing) => {
      const index = playing || ms > 0 ? lineAt(lines, ms) : -1;
      setActive((current) => (current === index ? current : index));
    });
  }, [player, lines]);

  useEffect(() => {
    if (follow && !editing && active >= 0) lineElement(active)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [active, follow, editing]);

  useEffect(() => {
    if (target === undefined) return;
    lineElement(target)?.scrollIntoView({ block: 'center' });
    player?.position(lines[target].startMs);
  }, [target, player, lines]);

  useEffect(() => {
    // Reading elsewhere in the transcript stops it following playback.
    const away = (event: Event) => {
      if (event.target instanceof Node && document.querySelector('.side-panel')?.contains(event.target)) return;
      if (event instanceof globalThis.KeyboardEvent && !['PageUp', 'PageDown', 'ArrowUp', 'ArrowDown', 'Home', 'End'].includes(event.key)) return;
      setFollow(false);
    };
    const events = ['wheel', 'touchmove', 'keydown'] as const;
    events.forEach((name) => window.addEventListener(name, away, { passive: true }));
    return () => events.forEach((name) => window.removeEventListener(name, away));
  }, []);

  const seekLine = useCallback(
    (index: number) => {
      setFollow(true);
      player?.seek(lines[index].startMs);
    },
    [player, lines],
  );
  const onSeek = player ? seekLine : undefined;
  const closeLine = useCallback(() => setOpen(-1), []);
  const saveLine = useCallback(
    async (index: number, text: string) => {
      const message = await editor!.save({ kind: 'line', at: lines[index].at, text });
      if (!message) {
        const shown = text.trim().replace(/\s+/g, ' ');
        setSavedText((current) => ({ base: lines, text: { ...(current.base === lines ? current.text : {}), [index]: shown } }));
      }
      return message;
    },
    [editor, lines],
  );

  if (lines.length === 0) {
    return (
      <div className="transcript">
        {(marks[-1] ?? []).map((mark, i) => (
          <MarkRow key={i} mark={mark} />
        ))}
        <p className="muted">This session has no transcript.</p>
      </div>
    );
  }
  return (
    <div className="transcript">
      {editor && (
        <div className="transcript-tools">
          <button
            type="button"
            className={editing ? 'button primary' : 'button'}
            aria-pressed={editing}
            onClick={() => {
              setEditing(!editing);
              setOpen(-1);
            }}
          >
            <IconPencil size={15} /> {editing ? 'Done editing' : 'Edit text'}
          </button>
          {editing && <span className="muted">Tap a line to edit it.</span>}
        </div>
      )}
      {list.map((item, i) =>
        item.kind === 'mark' ? (
          <MarkRow key={i} mark={item.mark} />
        ) : (
          <TurnView
            key={i}
            turn={item}
            lines={lines}
            active={active >= item.first && active <= item.last ? active : -1}
            target={target !== undefined && target >= item.first && target <= item.last ? target : -1}
            onSeek={onSeek}
            saved={saved}
            open={open >= item.first && open <= item.last ? open : -1}
            onOpen={editing ? setOpen : undefined}
            onClose={closeLine}
            onSave={saveLine}
          />
        ),
      )}
      {!follow && !editing && active >= 0 && (
        <button
          type="button"
          className="follow-button"
          onClick={() => {
            setFollow(true);
            lineElement(active)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
          }}
        >
          <IconLocate size={16} /> Back to current line
        </button>
      )}
    </div>
  );
}
