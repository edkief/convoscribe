import { describe, expect, it } from 'vitest';
import { EndpointSegmenter, normalizeText, type RecognizerBackend, type TimedToken } from './segmenter';

/** The app's `ScriptedBackend`: one `[text, isEndpoint, tokens?]` per accepted chunk, the last one held. */
class ScriptedBackend implements RecognizerBackend {
  private text = '';
  private endpoint = false;
  private tokens: TimedToken[] = [];
  resets = 0;
  finished = false;
  accepted: number[] = [];

  constructor(private readonly script: [string, boolean, TimedToken[]?][]) {}

  acceptWaveform(samples: Float32Array) {
    this.accepted.push(samples.length);
    const next = this.script.shift();
    if (next) [this.text, this.endpoint, this.tokens = []] = next;
  }
  decodeAvailable() {}
  currentText() {
    return this.text;
  }
  currentTokens() {
    return this.tokens;
  }
  isEndpoint() {
    return this.endpoint;
  }
  reset() {
    this.resets++;
    this.text = '';
    this.endpoint = false;
    this.tokens = [];
  }
  inputFinished() {
    this.finished = true;
  }
}

/** 100 ms at 16 kHz, the recorder's chunk. */
const chunk = () => new Float32Array(1600);
const partial = (text: string) => ({ kind: 'partial', text });
const final = (text: string, startMs: number, endMs: number, tokens: TimedToken[] = []) => ({
  kind: 'final',
  line: { text, startMs, endMs, tokens },
});

describe('normalizeText (the app’s TextNormalizer)', () => {
  it.each([
    ['  HELLO   I AM HERE ', 'Hello I am here'],
    ['bonjour à tous', 'Bonjour à tous'],
    ['', ''],
    ['Already Mixed', 'Already Mixed'],
    ['   \t \n  ', ''],
    ['I', 'I'],
    ['inline items', 'Inline items'],
    ['  1   2   3!  ', '1 2 3!'],
    ["L'ÉTÉ EST CHAUD", "L'été est chaud"],
  ])('%j → %j', (raw, expected) => {
    expect(normalizeText(raw)).toBe(expected);
  });
});

describe('EndpointSegmenter (the app’s, #164)', () => {
  it('sends partials, then a final line timed by its samples on an endpoint', () => {
    const be = new ScriptedBackend([
      ['HELLO', false],
      ['HELLO WORLD', false],
      ['HELLO WORLD', true],
    ]);
    const seg = new EndpointSegmenter(be);
    expect(seg.accept(chunk())).toEqual([partial('Hello')]);
    expect(seg.accept(chunk())).toEqual([partial('Hello world')]);
    expect(seg.accept(chunk())).toEqual([final('Hello world', 0, 300), partial('')]);
    expect(be.resets).toBe(1);
  });

  it('starts the next line where the last one ended, with its tokens moved onto the recording’s clock', () => {
    const be = new ScriptedBackend([
      ['HELLO', false],
      ['HELLO WORLD', false],
      ['HELLO WORLD', true],
      ['foo', false],
      ['foo', true, [{ text: ' foo', ms: 40 }]],
    ]);
    const seg = new EndpointSegmenter(be);
    seg.accept(chunk());
    seg.accept(chunk());
    seg.accept(chunk());
    seg.accept(chunk());
    expect(seg.accept(chunk())).toEqual([final('Foo', 300, 500, [{ text: ' foo', ms: 340 }]), partial('')]);
    expect(be.resets).toBe(2);
  });

  it('resets on an endpoint with no text but writes no line', () => {
    const be = new ScriptedBackend([['', true]]);
    expect(new EndpointSegmenter(be).accept(chunk())).toEqual([partial('')]);
    expect(be.resets).toBe(1);
  });

  it('sends a repeated partial once', () => {
    const seg = new EndpointSegmenter(new ScriptedBackend([['A', false], ['A', false], ['A', false]]));
    expect(seg.accept(chunk())).toEqual([partial('A')]);
    expect(seg.accept(chunk())).toEqual([]);
    expect(seg.accept(chunk())).toEqual([]);
  });

  it('closes the pending text on finish, ending at the last sample', () => {
    const be = new ScriptedBackend([['hello', false]]);
    const seg = new EndpointSegmenter(be);
    seg.accept(chunk());
    expect(seg.finish()).toEqual([final('Hello', 0, 100), partial('')]);
    expect(be.finished).toBe(true);
  });

  it('writes no line on finish when nothing is pending', () => {
    const seg = new EndpointSegmenter(new ScriptedBackend([['', false]]));
    seg.accept(chunk());
    expect(seg.finish()).toEqual([partial('')]);
  });

  it('ignores an empty chunk', () => {
    const be = new ScriptedBackend([['HELLO', false]]);
    const seg = new EndpointSegmenter(be);
    expect(seg.accept(new Float32Array(0))).toEqual([]);
    expect(seg.totalSamples).toBe(0);
    expect(be.accepted).toEqual([]);
    expect(seg.accept(chunk())).toEqual([partial('Hello')]);
  });
});
