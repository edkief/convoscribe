import { describe, expect, it } from 'vitest';
import { buildSessionManifest, parseNewSession, sessionTitle } from './session-create';

const SHA = 'a'.repeat(64);
// 2025-10-09 08:53:20 UTC.
const CREATED = 1_760_000_000_000;

const body = (over: Record<string, unknown> = {}) => ({
  syncId: '0f6c1c64-6a3e-4a63-9a39-6a4b0e0d2b11',
  audioSha: SHA,
  durationMs: 9000,
  createdAt: CREATED,
  language: 'en',
  lines: [
    { startMs: 4000, endMs: 9000, text: 'Hi, how are you?' },
    { startMs: 0, endMs: 4000, text: 'Hello there.', tokens: [{ ms: 0, text: 'Hello' }, { ms: 600, text: ' there.' }] },
  ],
  asrModel: 'asr-en-kroko-2025-08-06',
  ...over,
});

describe('parseNewSession (#163)', () => {
  it('reads a create request', () => {
    expect(parseNewSession(body({ title: 'Standup', zone: 'Europe/Paris' }))).toEqual({
      ...body({ title: 'Standup', zone: 'Europe/Paris' }),
      lines: [
        { startMs: 4000, endMs: 9000, text: 'Hi, how are you?', tokens: [] },
        { startMs: 0, endMs: 4000, text: 'Hello there.', tokens: [{ ms: 0, text: 'Hello' }, { ms: 600, text: ' there.' }] },
      ],
    });
    expect(parseNewSession(body({ asrModel: undefined, lines: [] }))).toMatchObject({ title: null, zone: null, asrModel: null, lines: [] });
  });

  it.each([
    ['a missing syncId', { syncId: undefined }, 'invalid_body'],
    ['a bad syncId', { syncId: 'a/b' }, 'invalid_sync_id'],
    ['a bad hash', { audioSha: 'ABC' }, 'invalid_sha256'],
    ['a negative length', { durationMs: -1 }, 'invalid_body'],
    ['a fractional length', { durationMs: 1.5 }, 'invalid_body'],
    ['no creation time', { createdAt: 0 }, 'invalid_body'],
    ['an unknown language shape', { language: 'english' }, 'invalid_body'],
    ['no lines', { lines: undefined }, 'invalid_body'],
    ['a line ending before it starts', { lines: [{ startMs: 5, endMs: 4, text: 'x' }] }, 'invalid_body'],
    ['a line without text', { lines: [{ startMs: 0, endMs: 4 }] }, 'invalid_body'],
    ['a bad word timing', { lines: [{ startMs: 0, endMs: 4, text: 'x', tokens: [{ ms: -1, text: 'x' }] }] }, 'invalid_body'],
    ['a title that is not text', { title: 5 }, 'invalid_body'],
    ['a long model name', { asrModel: 'm'.repeat(101) }, 'invalid_body'],
  ])('refuses %s', (_name, over, code) => {
    expect(() => parseNewSession(body(over))).toThrow(expect.objectContaining({ status: 400, code }));
  });
});

describe('sessionTitle (#163)', () => {
  it('names a session without a title by its time in the browser zone, as a default title', () => {
    expect(sessionTitle(null, CREATED, 'Europe/Paris')).toEqual({ title: 'Recording 2025-10-09 10:53', titleSource: 'default' });
    expect(sessionTitle('   ', CREATED, 'America/New_York')).toEqual({ title: 'Recording 2025-10-09 04:53', titleSource: 'default' });
    expect(sessionTitle(null, CREATED, null).title).toBe('Recording 2025-10-09 08:53');
    expect(sessionTitle(null, CREATED, 'Not/AZone').title).toBe('Recording 2025-10-09 08:53');
  });

  it("keeps the user's title, trimmed, capped and with {date} and {time} filled in", () => {
    expect(sessionTitle('  Standup {date} {time} ', CREATED, 'Asia/Tokyo')).toEqual({ title: 'Standup 2025-10-09 17:53', titleSource: 'user' });
    expect(sessionTitle('x'.repeat(150), CREATED, null).title).toHaveLength(100);
  });
});

describe('buildSessionManifest (#163)', () => {
  const audio = { sha256: SHA, size: 288_044 };

  it("writes the app's schema-1 manifest, every field in the app's order", () => {
    const manifest = buildSessionManifest(parseNewSession(body({ zone: 'Europe/Paris' })), audio);
    expect(Object.keys(manifest)).toEqual([
      'schema', 'syncId', 'title', 'titleSource', 'previousTitle', 'createdAt', 'durationMs', 'language', 'status',
      'numSpeakers', 'participants', 'asrEngine', 'asrModel', 'transcriptEdited', 'summaryTemplate', 'cleanedWith',
      'diarizedWith', 'diarizationStepMs', 'diarizationMs', 'audio', 'cleanAudio', 'lines', 'speakers', 'messages',
      'marks', 'tags',
    ]);
    expect(manifest).toMatchObject({
      schema: 1,
      title: 'Recording 2025-10-09 10:53',
      titleSource: 'default',
      createdAt: CREATED,
      durationMs: 9000,
      language: 'en',
      status: 'DIARIZATION_PENDING',
      asrEngine: 'browser',
      asrModel: 'asr-en-kroko-2025-08-06',
      audio,
      speakers: [],
    });
    expect(manifest.lines).toEqual([
      { startMs: 0, endMs: 4000, text: 'Hello there.', speakerIndex: null, tokens: [{ ms: 0, text: 'Hello' }, { ms: 600, text: ' there.' }], textEdited: false, speakerEdited: false },
      { startMs: 4000, endMs: 9000, text: 'Hi, how are you?', speakerIndex: null, tokens: [], textEdited: false, speakerEdited: false },
    ]);
    expect(Object.keys(manifest)).not.toContain('actionItems');
  });

  it('names no engine without a model, when the browser did not transcribe', () => {
    expect(buildSessionManifest(parseNewSession(body({ asrModel: null, lines: [] })), audio)).toMatchObject({ asrEngine: null, asrModel: null, lines: [] });
  });

  it('writes the same bytes for the same request', () => {
    const a = JSON.stringify(buildSessionManifest(parseNewSession(body()), audio));
    const b = JSON.stringify(buildSessionManifest(parseNewSession(JSON.parse(JSON.stringify(body()))), audio));
    expect(a).toBe(b);
  });
});
