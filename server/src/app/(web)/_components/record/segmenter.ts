/**
 * The app's live transcript logic, ported for the browser (#164) so a
 * session recorded in the web app has the lines a phone would write: the
 * app's `TextNormalizer` and `EndpointSegmenter`. Kept free of sherpa-onnx
 * so it is tested with a scripted backend, as in the app.
 */

import { SAMPLE_RATE } from './asr-catalog';

/** One recognised piece (`" the"`, `"ing"`) and when it was spoken. */
export type TimedToken = { text: string; ms: number };

/** A closed utterance: a transcript line, with its tokens on the recording's clock. */
export type FinalLine = { text: string; startMs: number; endMs: number; tokens: TimedToken[] };

export type TranscriptEvent = { kind: 'partial'; text: string } | { kind: 'final'; line: FinalLine };

/** What the segmenter needs from a streaming recognizer; the app's `RecognizerBackend`. */
export interface RecognizerBackend {
  acceptWaveform(samples: Float32Array): void;
  /** Decode everything the recognizer is ready for. */
  decodeAvailable(): void;
  currentText(): string;
  /** The current utterance's tokens, in ms since the last [reset]. */
  currentTokens(): TimedToken[];
  isEndpoint(): boolean;
  reset(): void;
  inputFinished(): void;
}

const WHITESPACE_RUN = /\s+/g;
const STANDALONE_I = /\bi\b/g;
const LETTER = /\p{L}/u;
const LOWER = /\p{Ll}/u;

const capitaliseFirst = (text: string) => {
  const [first, ...rest] = Array.from(text);
  return first.toUpperCase() + rest.join('');
};

/**
 * The app's `TextNormalizer.normalize`: trim and collapse whitespace; text
 * with letters but no lower-case letter is lower-cased with the pronoun `i`
 * put back to `I`; the first character is capitalised.
 */
export function normalizeText(raw: string): string {
  const trimmed = raw.trim().replace(WHITESPACE_RUN, ' ');
  if (!trimmed) return '';
  if (!LETTER.test(trimmed)) return trimmed;
  const allUpper = !LOWER.test(trimmed);
  if (allUpper) return capitaliseFirst(trimmed.toLowerCase().replace(STANDALONE_I, 'I'));
  return capitaliseFirst(trimmed);
}

/** The app's `samplesToMs`, rounding down. */
export const samplesToMs = (samples: number, sampleRate = SAMPLE_RATE) => Math.floor((samples * 1000) / sampleRate);

/**
 * The app's `EndpointSegmenter`: turns a recognizer into partial and
 * final events, timing each final line by the samples it has been given.
 * An endpoint with no text still resets the recognizer; identical
 * consecutive partials are sent once.
 */
export class EndpointSegmenter {
  /** Samples pushed through [accept] so far. */
  totalSamples = 0;
  private segmentStartSample = 0;
  private lastPartialText = '';

  constructor(
    private readonly backend: RecognizerBackend,
    private readonly sampleRate = SAMPLE_RATE,
  ) {}

  accept(samples: Float32Array): TranscriptEvent[] {
    if (samples.length === 0) return [];
    this.totalSamples += samples.length;
    this.backend.acceptWaveform(samples);
    this.backend.decodeAvailable();
    const text = normalizeText(this.backend.currentText());
    const events: TranscriptEvent[] = [];
    if (this.backend.isEndpoint()) {
      if (text.trim()) events.push(this.finalEvent(text));
      this.backend.reset();
      this.segmentStartSample = this.totalSamples;
      events.push({ kind: 'partial', text: '' });
      this.lastPartialText = '';
    } else if (text !== this.lastPartialText) {
      events.push({ kind: 'partial', text });
      this.lastPartialText = text;
    }
    return events;
  }

  /** No more audio: the pending text as a final line, then an empty partial. */
  finish(): TranscriptEvent[] {
    this.backend.inputFinished();
    this.backend.decodeAvailable();
    const text = normalizeText(this.backend.currentText());
    const events: TranscriptEvent[] = [];
    if (text.trim()) events.push(this.finalEvent(text));
    events.push({ kind: 'partial', text: '' });
    this.lastPartialText = '';
    return events;
  }

  private finalEvent(text: string): TranscriptEvent {
    const startMs = samplesToMs(this.segmentStartSample, this.sampleRate);
    return {
      kind: 'final',
      line: {
        text,
        startMs,
        endMs: samplesToMs(this.totalSamples, this.sampleRate),
        tokens: this.backend.currentTokens().map((t) => ({ text: t.text, ms: t.ms + startMs })),
      },
    };
  }
}
