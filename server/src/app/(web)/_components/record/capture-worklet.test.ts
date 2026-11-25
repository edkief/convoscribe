import { describe, expect, it } from 'vitest';
import { CAPTURE_PROCESSOR, CAPTURE_WORKLET, CHUNK_SAMPLES } from './capture-worklet';

type Sent = { samples?: Float32Array; rms?: number; flushed?: boolean };

/** The worklet's processor, run against fake worklet globals at the context rate `rate`. */
function processor(rate: number, targetRate = 16_000) {
  const sent: Sent[] = [];
  let Processor: new (options: unknown) => {
    process: (inputs: Float32Array[][]) => boolean;
    port: { onmessage: (e: { data: unknown }) => void };
  } = undefined!;
  class FakeProcessor {
    port = { postMessage: (data: Sent) => sent.push(data), onmessage: (_e: { data: unknown }) => {} };
  }
  new Function('AudioWorkletProcessor', 'registerProcessor', 'sampleRate', CAPTURE_WORKLET)(
    FakeProcessor,
    (name: string, cls: typeof Processor) => {
      expect(name).toBe(CAPTURE_PROCESSOR);
      Processor = cls;
    },
    rate,
  );
  const node = new Processor({ processorOptions: { targetRate } });
  const feed = (values: number[]) => {
    for (let i = 0; i < values.length; i += 128) node.process([[Float32Array.from(values.slice(i, i + 128))]]);
  };
  return { node, sent, feed };
}

describe('capture worklet (#164)', () => {
  it('sends 100 ms chunks with their level at 16 kHz', () => {
    const { sent, feed } = processor(16_000);
    feed(Array.from({ length: CHUNK_SAMPLES * 2 + 100 }, () => 0.5));
    expect(sent.map((s) => s.samples!.length)).toEqual([CHUNK_SAMPLES, CHUNK_SAMPLES]);
    expect(sent[0].rms).toBeCloseTo(0.5);
  });

  it('sends what it holds on flush, then says so', () => {
    const { node, sent, feed } = processor(16_000);
    feed(Array.from({ length: 300 }, () => 0.1));
    node.port.onmessage({ data: 'flush' });
    expect(sent.map((s) => s.samples?.length ?? 'flushed')).toEqual([300, 'flushed']);
  });

  it('averages a 48 kHz input down to 16 kHz', () => {
    const { node, sent, feed } = processor(48_000);
    // A ramp: each output sample is the mean of three inputs.
    feed(Array.from({ length: 4800 }, (_, i) => i / 4800));
    node.port.onmessage({ data: 'flush' });
    const out = sent[0].samples!;
    expect(out.length).toBe(1600);
    expect(out[0]).toBeCloseTo(1 / 4800);
    expect(out[1]).toBeCloseTo(4 / 4800);
  });
});
