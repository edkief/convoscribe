/** The name the capture processor registers under. */
export const CAPTURE_PROCESSOR = 'convoscribe-capture';

/** Samples per chunk sent to the page: 100 ms at 16 kHz, the app's chunk. */
export const CHUNK_SAMPLES = 1600;

/**
 * The AudioWorklet that taps the microphone for the Record page (#164),
 * as source text: a worklet loads from a URL, and a Blob URL of this needs
 * no bundler support. It sends `{ samples, rms }` per 100 ms chunk of mono
 * audio at `processorOptions.targetRate`, and on `'flush'` sends what it
 * holds, then `{ flushed: true }`.
 *
 * The page asks for a 16 kHz context, so the browser resamples. A browser
 * that refuses (Firefox, with a microphone at another rate) runs it at the
 * native rate, and the processor averages input samples down to the target
 * rate instead: cruder, but best effort is all that browser gets.
 */
export const CAPTURE_WORKLET = `
class Capture extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const target = (options.processorOptions && options.processorOptions.targetRate) || sampleRate;
    this.ratio = sampleRate / target;
    this.chunk = new Float32Array(${CHUNK_SAMPLES});
    this.filled = 0;
    this.acc = 0;
    this.count = 0;
    this.phase = 0;
    this.port.onmessage = (e) => {
      if (e.data === 'flush') {
        this.send();
        this.port.postMessage({ flushed: true });
      }
    };
  }

  send() {
    if (this.filled === 0) return;
    const samples = this.chunk.slice(0, this.filled);
    let sum = 0;
    for (let i = 0; i < samples.length; i++) sum += samples[i] * samples[i];
    this.port.postMessage({ samples, rms: Math.sqrt(sum / samples.length) }, [samples.buffer]);
    this.filled = 0;
  }

  push(value) {
    this.chunk[this.filled++] = value;
    if (this.filled === this.chunk.length) this.send();
  }

  process(inputs) {
    const channel = inputs[0] && inputs[0][0];
    if (!channel) return true;
    if (this.ratio === 1) {
      for (let i = 0; i < channel.length; i++) this.push(channel[i]);
      return true;
    }
    for (let i = 0; i < channel.length; i++) {
      this.acc += channel[i];
      this.count++;
      this.phase += 1;
      if (this.phase >= this.ratio) {
        this.push(this.acc / this.count);
        this.phase -= this.ratio;
        this.acc = 0;
        this.count = 0;
      }
    }
    return true;
  }
}
registerProcessor('${CAPTURE_PROCESSOR}', Capture);
`;
