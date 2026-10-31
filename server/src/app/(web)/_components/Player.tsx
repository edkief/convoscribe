'use client';

import { createContext, useContext, type ReactNode } from 'react';

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
  audio.currentTime = ms / 1000;
  void audio.play().catch(() => {});
}

function subscribe(listener: (ms: number, playing: boolean) => void) {
  const audio = element();
  if (!audio) return () => {};
  const update = () => listener(audio.currentTime * 1000, !audio.paused);
  const events = ['timeupdate', 'seeked', 'pause'] as const;
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

/** The player, kept in view while the page scrolls; nothing without a recording. */
export function PlayerBar() {
  const player = usePlayer();
  if (!player) return null;
  return (
    <div className="player">
      <audio id={AUDIO_ID} src={player.src} controls preload="metadata" />
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
