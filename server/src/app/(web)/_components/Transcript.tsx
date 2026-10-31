'use client';

import { Fragment, memo, useEffect, useRef, useState } from 'react';
import type { Mark, TranscriptLine } from '@/lib/transcript-export';
import { usePlayer } from './Player';

type Props = {
  lines: (TranscriptLine & { stamp: string })[];
  /** Marks by the index of the line they go above; `-1` when there are no lines. */
  marks: Record<number, (Mark & { stamp: string })[]>;
};

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

const MarkNote = ({ mark }: { mark: Mark & { stamp: string } }) => (
  <div className="mark">
    <span aria-hidden>{'⚑'}</span> <span className="time">{mark.stamp}</span> Mark{mark.note ? `: ${mark.note}` : ''}
    {mark.expandedNote && (
      <p className={mark.expandedStale ? 'mark-note stale' : 'mark-note'}>
        {mark.expandedNote}
        {mark.expandedStale && <em> (written before the transcript changed)</em>}
      </p>
    )}
  </div>
);

const Line = memo(function Line({
  line,
  active,
  onSeek,
}: {
  line: Props['lines'][number];
  active: boolean;
  onSeek?: (ms: number) => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (active) ref.current?.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
  }, [active]);
  const body = (
    <>
      <span className="time">{line.stamp}</span>
      {line.speaker && (
        <span className="speaker" data-speaker={(line.speakerIndex ?? 0) % 6}>
          {line.speaker}
        </span>
      )}
      <span className="text">{line.text}</span>
      {line.edited && <span className="edited">edited</span>}
    </>
  );
  return (
    <div ref={ref} className={active ? 'line active' : 'line'}>
      {onSeek ? (
        <button type="button" onClick={() => onSeek(line.startMs)} title="Play from here">
          {body}
        </button>
      ) : (
        <div className="line-body">{body}</div>
      )}
    </div>
  );
});

/**
 * The transcript, one line per segment with its marks above it (#80).
 * With a recording, tapping a line plays from it, and the line being
 * played is highlighted and kept in view.
 */
export function Transcript({ lines, marks }: Props) {
  const player = usePlayer();
  const [active, setActive] = useState(-1);
  useEffect(() => {
    if (!player) return;
    return player.subscribe((ms, playing) => {
      const index = playing || ms > 0 ? lineAt(lines, ms) : -1;
      setActive((current) => (current === index ? current : index));
    });
  }, [player, lines]);
  if (lines.length === 0) {
    return (
      <>
        {marks[-1]?.map((mark, i) => <MarkNote key={i} mark={mark} />)}
        <p className="empty">This session has no transcript.</p>
      </>
    );
  }
  return (
    <div className="transcript">
      {lines.map((line, i) => (
        <Fragment key={i}>
          {marks[i]?.map((mark, j) => <MarkNote key={j} mark={mark} />)}
          <Line line={line} active={i === active} onSeek={player?.seek} />
        </Fragment>
      ))}
    </div>
  );
}
