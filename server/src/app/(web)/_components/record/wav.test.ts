import { describe, expect, it } from 'vitest';
import { pcmDurationMs, toPcm16, wavHeader } from './wav';

describe('wavHeader (#164)', () => {
  it('describes 16-bit PCM, 16 kHz mono, as the app records', () => {
    const header = wavHeader(32_000);
    const view = new DataView(header.buffer);
    const text = (offset: number) => String.fromCharCode(...header.subarray(offset, offset + 4));
    expect(header.length).toBe(44);
    expect([text(0), text(8), text(12), text(36)]).toEqual(['RIFF', 'WAVE', 'fmt ', 'data']);
    expect(view.getUint32(4, true)).toBe(36 + 32_000);
    expect(view.getUint16(20, true)).toBe(1);
    expect(view.getUint16(22, true)).toBe(1);
    expect(view.getUint32(24, true)).toBe(16_000);
    expect(view.getUint32(28, true)).toBe(32_000);
    expect(view.getUint16(32, true)).toBe(2);
    expect(view.getUint16(34, true)).toBe(16);
    expect(view.getUint32(40, true)).toBe(32_000);
    expect(pcmDurationMs(32_000)).toBe(1000);
  });

  it('converts float samples to clipped little-endian 16-bit', () => {
    const bytes = toPcm16(Float32Array.from([0, 1, -1, 2, -2, 0.5]));
    const view = new DataView(bytes.buffer);
    expect(Array.from({ length: 6 }, (_, i) => view.getInt16(i * 2, true))).toEqual([0, 32767, -32768, 32767, -32768, 16384]);
  });
});
