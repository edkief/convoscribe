/**
 * What the web app's Record page transcribes with (#164): sherpa-onnx built
 * for the browser (scripts/build-sherpa-onnx-wasm.sh) and the same Kroko
 * streaming zipformers as the app's CPU engine, downloaded from the same
 * place. Keep in step with the app's `ModelCatalog` (ids, URLs, sizes) and
 * `SherpaAsrConfigs` (recognizer settings).
 */

/** The sherpa-onnx the browser runtime is built from; also in server/Dockerfile. */
export const SHERPA_WASM_VERSION = '1.13.8';

/** Where the server serves the runtime (public/sherpa-onnx/<version>/). */
export const RUNTIME_BASE = `/sherpa-onnx/${SHERPA_WASM_VERSION}/`;

/** The runtime's files, by role. */
export const RUNTIME_FILES = {
  factory: 'sherpa-onnx-wasm-main-asr.js',
  wasm: 'sherpa-onnx-wasm-main-asr.wasm',
  api: 'sherpa-onnx-asr.mjs',
} as const;

export const SAMPLE_RATE = 16_000;

export type ModelFile = { name: string; url: string; size: number };

export type AsrBundle = {
  /** The app's bundle id, also written as the session's `asrModel`. */
  id: string;
  language: 'en' | 'fr';
  label: string;
  files: ModelFile[];
};

const base = (language: string) =>
  `https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-${language}-kroko-2025-08-06/resolve/main/`;

const kroko = (language: 'en' | 'fr', label: string, tokensSize: number): AsrBundle => ({
  id: `asr-${language}-kroko-2025-08-06`,
  language,
  label,
  files: [
    { name: 'encoder.onnx', url: `${base(language)}encoder.onnx`, size: 70_092_599 },
    { name: 'decoder.onnx', url: `${base(language)}decoder.onnx`, size: 617_488 },
    { name: 'joiner.onnx', url: `${base(language)}joiner.onnx`, size: 336_817 },
    { name: 'tokens.txt', url: `${base(language)}tokens.txt`, size: tokensSize },
  ],
});

export const BUNDLES: readonly AsrBundle[] = [kroko('en', 'English', 6_310), kroko('fr', 'French', 5_415)];

export const bundleFor = (language: string): AsrBundle | undefined => BUNDLES.find((b) => b.language === language);

/** Bytes a bundle's model files take, for the download button. */
export const bundleBytes = (bundle: AsrBundle): number => bundle.files.reduce((sum, f) => sum + f.size, 0);

/**
 * The recognizer settings of the app's CPU engine (`SherpaAsrConfigs.cpu`),
 * in sherpa-onnx's JavaScript config shape, for model files at `dir` in the
 * module's filesystem. One thread: the browser build has none.
 */
export function recognizerConfig(dir: string) {
  return {
    featConfig: { sampleRate: SAMPLE_RATE, featureDim: 80 },
    modelConfig: {
      transducer: { encoder: `${dir}/encoder.onnx`, decoder: `${dir}/decoder.onnx`, joiner: `${dir}/joiner.onnx` },
      tokens: `${dir}/tokens.txt`,
      numThreads: 1,
      provider: 'cpu',
      modelType: 'zipformer2',
      debug: 0,
    },
    decodingMethod: 'greedy_search',
    enableEndpoint: 1,
    rule1MinTrailingSilence: 2.4,
    rule2MinTrailingSilence: 0.8,
    rule3MinUtteranceLength: 20,
  };
}
