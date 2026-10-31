'use client';

import { createContext, useContext, useEffect, useState, type CSSProperties, type ReactNode } from 'react';
import { IconPause, IconPlay, IconSkip, IconVolume } from './Icons';

const AUDIO_ID = 'session-audio';

const element = () => document.getElementById(AUDIO_ID) as HTMLAudioElement | null;

type PlayerApi = {
  src: string;
  /** Jump to `ms` and play. */
  seek(ms: number): void;
  /** Call `listener` with the playback position (ms) as it moves; returns the unsubscribe. */
  subscribe(listener: (ms: number, playing: boolean) => void): () => void;
};

const PlayerContext = createContext<PlayerApi | null>(null);

export const usePlayer = () => useContext(PlayerContext);

function seek(ms: number) {
  const audio = element();
  if (!audio) return;
  audio.currentTime = Math.max(0, ms / 1000);
  void audio.play().catch(() => {});
}

function subscribe(listener: (ms: number, playing: boolean) => void) {
  const audio = element();
  if (!audio) return () => {};
  const update = () => listener(audio.currentTime * 1000, !audio.paused);
  const events = ['timeupdate', 'seeked', 'pause', 'play'] as const;
  events.forEach((name) => audio.addEventListener(name, update));
  return () => events.forEach((name) => audio.removeEventListener(name, update));
}

/**
 * The session's recording and the seeking its transcript, marks and action
 * items do (#80); [PlayerBar] shows the player. Without a recording the
 * children render as they are, with times as plain text.
 */
export function PlayerProvider({ src, children }: { src: string | null; children: ReactNode }) {
  return <PlayerContext.Provider value={src === null ? null : { src, seek, subscribe }}>{children}</PlayerContext.Provider>;
}

const RATES = [1, 1.25, 1.5, 2, 0.75];

/** `m:ss`, or `h:mm:ss` from an hour, for the player's clock. */
function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(Number.isFinite(seconds) ? seconds : 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, '0');
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${ss}` : `${m}:${ss}`;
}

const typing = (target: EventTarget | null) =>
  target instanceof HTMLElement && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(target.tagName));

type Props = {
  /** The session's length from its manifest, until the recording's own is known. */
  durationMs: number | null;
  /** Marks to show on the timeline. */
  marks: { atMs: number; label: string }[];
};

/**
 * The player, pinned to the bottom of the page: play, back 10 s, forward
 * 30 s, a timeline with the session's marks, speed and mute. Space plays
 * and pauses, the arrow keys step 5 s. Nothing without a recording.
 */
export function PlayerBar({ durationMs, marks }: Props) {
  const player = usePlayer();
  const [playing, setPlaying] = useState(false);
  const [time, setTime] = useState(0);
  const [duration, setDuration] = useState((durationMs ?? 0) / 1000);
  const [rate, setRate] = useState(1);
  const [muted, setMuted] = useState(false);

  useEffect(() => {
    if (!player) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.altKey || event.ctrlKey || event.metaKey || typing(event.target)) return;
      const audio = element();
      if (!audio) return;
      if (event.key === ' ' && !(event.target instanceof HTMLButtonElement)) {
        event.preventDefault();
        if (audio.paused) void audio.play().catch(() => {});
        else audio.pause();
      } else if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
        if (event.target instanceof HTMLInputElement) return;
        event.preventDefault();
        audio.currentTime = Math.max(0, audio.currentTime + (event.key === 'ArrowLeft' ? -5 : 5));
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [player]);

  if (!player) return null;
  const total = duration > 0 ? duration : (durationMs ?? 0) / 1000;
  const progress = total > 0 ? Math.min(100, (time / total) * 100) : 0;
  const skip = (seconds: number) => {
    const audio = element();
    if (audio) audio.currentTime = Math.min(Math.max(0, audio.currentTime + seconds), total || Infinity);
  };
  const toggle = () => {
    const audio = element();
    if (!audio) return;
    if (audio.paused) void audio.play().catch(() => {});
    else audio.pause();
  };

  return (
    <div className="player-bar" role="region" aria-label="Player">
      <audio
        id={AUDIO_ID}
        src={player.src}
        preload="metadata"
        onPlay={() => setPlaying(true)}
        onPause={() => setPlaying(false)}
        onEnded={() => setPlaying(false)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)}
        onSeeked={(e) => setTime(e.currentTarget.currentTime)}
        onLoadedMetadata={(e) => Number.isFinite(e.currentTarget.duration) && setDuration(e.currentTarget.duration)}
        onDurationChange={(e) => Number.isFinite(e.currentTarget.duration) && setDuration(e.currentTarget.duration)}
      />
      <div className="player-controls">
        <button type="button" className="icon-button" onClick={() => skip(-10)} title="Back 10 seconds (←)" aria-label="Back 10 seconds">
          <IconSkip seconds={10} back size={26} />
        </button>
        <button
          type="button"
          className="play-button"
          onClick={toggle}
          title={playing ? 'Pause (space)' : 'Play (space)'}
          aria-label={playing ? 'Pause' : 'Play'}
        >
          {playing ? <IconPause size={20} /> : <IconPlay size={20} />}
        </button>
        <button type="button" className="icon-button" onClick={() => skip(30)} title="Forward 30 seconds (→)" aria-label="Forward 30 seconds">
          <IconSkip seconds={30} size={26} />
        </button>
      </div>
      <span className="player-time">{clock(time)}</span>
      <div className="timeline">
        <input
          type="range"
          min={0}
          max={total || 1}
          step={0.1}
          value={Math.min(time, total || 1)}
          onChange={(e) => {
            const audio = element();
            if (audio) audio.currentTime = Number(e.currentTarget.value);
          }}
          style={{ '--progress': `${progress}%` } as CSSProperties}
          aria-label="Position"
          aria-valuetext={`${clock(time)} of ${clock(total)}`}
        />
        {total > 0 &&
          marks.map((mark, i) => (
            <button
              key={i}
              type="button"
              className="timeline-mark"
              style={{ left: `${Math.min(100, (mark.atMs / 1000 / total) * 100)}%` }}
              onClick={() => player.seek(mark.atMs)}
              title={mark.label}
              aria-label={`Mark: ${mark.label}`}
            />
          ))}
      </div>
      <span className="player-time total">{clock(total)}</span>
      <button
        type="button"
        className="rate-button"
        onClick={() => {
          const nextRate = RATES[(RATES.indexOf(rate) + 1) % RATES.length];
          const audio = element();
          if (audio) audio.playbackRate = nextRate;
          setRate(nextRate);
        }}
        title="Playback speed"
        aria-label={`Playback speed ${rate}×`}
      >
        {rate}×
      </button>
      <button
        type="button"
        className="icon-button"
        onClick={() => {
          const audio = element();
          if (audio) audio.muted = !muted;
          setMuted(!muted);
        }}
        title={muted ? 'Unmute' : 'Mute'}
        aria-label={muted ? 'Unmute' : 'Mute'}
      >
        <IconVolume muted={muted} size={20} />
      </button>
    </div>
  );
}

/** A time that plays the recording from there, or plain text without one. */
export function SeekTime({ ms, label }: { ms: number; label: string }) {
  const player = usePlayer();
  if (!player) return <span className="time">{label}</span>;
  return (
    <button type="button" className="time seek" onClick={() => player.seek(ms)} title="Play from here">
      {label}
    </button>
  );
}
