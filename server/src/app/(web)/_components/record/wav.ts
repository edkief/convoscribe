import { SAMPLE_RATE } from './asr-catalog';

/** Bytes before the samples in the app's WAV files. */
export const WAV_HEADER_BYTES = 44;

/**
 * The 44-byte header of a 16-bit PCM, 16 kHz mono WAV holding
 * `dataBytes` of samples: the app's recording format, so a phone reads a
 * recording made in the web app (#164) as its own.
 */
export function wavHeader(dataBytes: number, sampleRate = SAMPLE_RATE): Uint8Array {
  const header = new Uint8Array(WAV_HEADER_BYTES);
  const view = new DataView(header.buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i++) header[offset + i] = text.charCodeAt(i);
  };
  ascii(0, 'RIFF');
  view.setUint32(4, 36 + dataBytes, true);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  view.setUint32(16, 16, true); // fmt chunk size
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  ascii(36, 'data');
  view.setUint32(40, dataBytes, true);
  return header;
}

/** Float samples in [-1, 1] as 16-bit PCM, clipped, little-endian bytes. */
export function toPcm16(samples: Float32Array): Uint8Array {
  const bytes = new Uint8Array(samples.length * 2);
  const view = new DataView(bytes.buffer);
  for (let i = 0; i < samples.length; i++) {
    const s = Math.max(-1, Math.min(1, samples[i]));
    view.setInt16(i * 2, s < 0 ? Math.round(s * 0x8000) : Math.round(s * 0x7fff), true);
  }
  return bytes;
}

/** The length of a recording with `dataBytes` of 16-bit mono samples. */
export const pcmDurationMs = (dataBytes: number, sampleRate = SAMPLE_RATE) => Math.floor((dataBytes / 2) * 1000 / sampleRate);
