import type { FinalLine } from './segmenter';
import type { QueuedRecording } from './upload-queue';

/**
 * A recording in progress, noted in IndexedDB when it starts (#165): with
 * its WAV in OPFS, flushed every few seconds, and the transcript lines
 * saved as they are finished, it is what recovering the recording after a
 * reload or a crashed tab needs. Stop turns it into a queued upload.
 */
export type Draft = {
  syncId: string;
  createdAt: number;
  language: string;
  zone: string | null;
  /** The browser model transcribing it, or null for a recording without a live transcript. */
  asrModel: string | null;
  /** The live transcript was stopped during the recording; it is recovered without one. */
  transcriptDropped: boolean;
};

/** The Web Lock a tab holds while it records `syncId`: a draft whose lock nobody holds was interrupted. */
export const recordingLock = (syncId: string) => `convoscribe-recording:${syncId}`;

/** A recovered recording shorter than this is thrown away rather than offered. */
export const MIN_RECOVERED_MS = 1000;

/** The drafts no tab is recording: those to offer to recover, oldest first. */
export function interruptedDrafts(drafts: Draft[], heldLocks: ReadonlySet<string>): Draft[] {
  return drafts.filter((draft) => !heldLocks.has(recordingLock(draft.syncId))).sort((a, b) => a.createdAt - b.createdAt);
}

/**
 * The saved lines that fall within the recovered audio: the WAV was last
 * flushed a few seconds before the crash, so a line may end after it. Lines
 * starting past the end are dropped, the rest are cut to it.
 */
export function linesWithin(lines: FinalLine[], durationMs: number): FinalLine[] {
  return [...lines]
    .sort((a, b) => a.startMs - b.startMs)
    .filter((line) => line.startMs < durationMs)
    .map((line) =>
      line.endMs <= durationMs
        ? line
        : { ...line, endMs: durationMs, tokens: line.tokens.filter((token) => token.ms <= durationMs) },
    );
}

/** The upload a recovered draft becomes; `lines` as saved, `now` the time it is queued. */
export function recoveredItem(
  draft: Draft,
  lines: FinalLine[],
  file: { sha256: string; size: number; durationMs: number },
  now: number,
): QueuedRecording {
  const transcript = draft.asrModel !== null && !draft.transcriptDropped;
  return {
    syncId: draft.syncId,
    audioSha: file.sha256,
    size: file.size,
    durationMs: file.durationMs,
    createdAt: draft.createdAt,
    language: draft.language,
    zone: draft.zone,
    asrModel: transcript ? draft.asrModel : null,
    lines: transcript ? linesWithin(lines, file.durationMs) : [],
    uploadId: null,
    stored: false,
    failed: null,
    queuedAt: now,
  };
}
