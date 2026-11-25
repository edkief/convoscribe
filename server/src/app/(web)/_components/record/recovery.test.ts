import { describe, expect, it } from 'vitest';
import { interruptedDrafts, linesWithin, orphans, recordingLock, recoveredItem, type Draft } from './recovery';
import type { FinalLine } from './segmenter';
import { recoveredDataBytes, WAV_HEADER_BYTES } from './wav';

const draft = (syncId: string, patch: Partial<Draft> = {}): Draft => ({
  syncId,
  createdAt: 1_700_000_000_000,
  language: 'en',
  zone: 'Europe/Paris',
  asrModel: 'asr-en-kroko-2025-08-06',
  transcriptDropped: false,
  ...patch,
});

const line = (startMs: number, endMs: number): FinalLine => ({
  text: `at ${startMs}`,
  startMs,
  endMs,
  tokens: [
    { text: ' at', ms: startMs },
    { text: ` ${startMs}`, ms: endMs },
  ],
});

const FILE = { sha256: 'a'.repeat(64), size: WAV_HEADER_BYTES + 32_000 * 10, durationMs: 10_000 };

describe('recovery (#165)', () => {
  it('offers the drafts no tab is recording, oldest first', () => {
    const live = draft('live', { createdAt: 1 });
    const older = draft('older', { createdAt: 2 });
    const newer = draft('newer', { createdAt: 3 });
    expect(interruptedDrafts([newer, live, older], new Set([recordingLock('live')])).map((d) => d.syncId)).toEqual(['older', 'newer']);
  });

  it('keeps the lines within the recovered audio, cutting one that runs past it', () => {
    const kept = linesWithin([line(9_000, 11_000), line(0, 4_000), line(10_000, 12_000)], 10_000);
    expect(kept.map((l) => [l.startMs, l.endMs])).toEqual([
      [0, 4_000],
      [9_000, 10_000],
    ]);
    expect(kept[1].tokens.map((t) => t.ms)).toEqual([9_000]);
  });

  it('queues a recovered recording with its transcript', () => {
    const item = recoveredItem(draft('a'), [line(0, 4_000), line(12_000, 13_000)], FILE, 42);
    expect(item).toMatchObject({
      syncId: 'a',
      audioSha: FILE.sha256,
      size: FILE.size,
      durationMs: 10_000,
      createdAt: 1_700_000_000_000,
      asrModel: 'asr-en-kroko-2025-08-06',
      uploadId: null,
      stored: false,
      failed: null,
      queuedAt: 42,
    });
    expect(item.lines).toHaveLength(1);
  });

  it('queues it without a transcript when the live transcript was off or stopped', () => {
    for (const d of [draft('off', { asrModel: null }), draft('dropped', { transcriptDropped: true })]) {
      const item = recoveredItem(d, [line(0, 4_000)], FILE, 42);
      expect(item.asrModel).toBeNull();
      expect(item.lines).toEqual([]);
    }
  });

  it('cuts a crashed WAV to its last whole sample', () => {
    expect(recoveredDataBytes(WAV_HEADER_BYTES + 1001)).toBe(1000);
    expect(recoveredDataBytes(WAV_HEADER_BYTES)).toBe(0);
    expect(recoveredDataBytes(10)).toBe(0);
  });
});

describe('orphans (#165)', () => {
  it('finds recordings neither queued nor in progress, and drafts whose recording is gone', () => {
    const result = orphans(
      ['queued', 'draft', 'stray', 'live'],
      ['queued'],
      [draft('draft'), draft('live'), draft('lost'), draft('starting')],
      new Set([recordingLock('live'), recordingLock('starting')]),
    );
    expect(result).toEqual({ files: ['stray'], drafts: ['lost'] });
  });
});
