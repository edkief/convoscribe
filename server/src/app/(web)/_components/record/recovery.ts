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
