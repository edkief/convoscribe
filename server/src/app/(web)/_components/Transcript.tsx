'use client';

import { memo, useCallback, useEffect, useMemo, useState, type KeyboardEvent } from 'react';
import type { Mark, TranscriptLine } from '@/lib/transcript-export';
import { Avatar } from './Avatar';
import { IconFlag, IconLocate } from './Icons';
import { usePlayer } from './Player';

type Line = TranscriptLine & { stamp: string };
type StampedMark = Mark & { stamp: string };

type Props = {
  lines: Line[];
  /** Marks by the index of the line they go above; `-1` when there are no lines. */
  marks: Record<number, StampedMark[]>;
  /** Each speaker index's position in the session, for its colour. */
  tones: Record<number, number>;
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

const TurnView = memo(function TurnView({
  turn,
  lines,
  active,
  onSeek,
}: {
  turn: Turn;
  lines: Line[];
  /** The playing line, when it is in this turn; else -1. */
  active: number;
  onSeek?: (index: number) => void;
}) {
  const own = lines.slice(turn.first, turn.last + 1);
  const key = (index: number) => (event: KeyboardEvent) => {
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      onSeek?.(index);
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
            const className = index === active ? 'seg active' : 'seg';
            return onSeek ? (
              <span
                key={index}
                data-line={index}
                className={className}
                role="button"
                tabIndex={0}
                title={`Play from ${line.stamp}`}
                onClick={() => onSeek(index)}
                onKeyDown={key(index)}
              >
                {line.text}{' '}
              </span>
            ) : (
              <span key={index} data-line={index} className={className}>
                {line.text}{' '}
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
 * away, when a button brings it back.
 */
export function Transcript({ lines, marks, tones }: Props) {
  const player = usePlayer();
  const [active, setActive] = useState(-1);
  const [follow, setFollow] = useState(true);
  const list = useMemo(() => items(lines, marks, tones), [lines, marks, tones]);

  useEffect(() => {
    if (!player) return;
    return player.subscribe((ms, playing) => {
      const index = playing || ms > 0 ? lineAt(lines, ms) : -1;
      setActive((current) => (current === index ? current : index));
    });
  }, [player, lines]);

  useEffect(() => {
    if (follow && active >= 0) lineElement(active)?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [active, follow]);

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
      {list.map((item, i) =>
        item.kind === 'mark' ? (
          <MarkRow key={i} mark={item.mark} />
        ) : (
          <TurnView
            key={i}
            turn={item}
            lines={lines}
            active={active >= item.first && active <= item.last ? active : -1}
            onSeek={onSeek}
          />
        ),
      )}
      {!follow && active >= 0 && (
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
