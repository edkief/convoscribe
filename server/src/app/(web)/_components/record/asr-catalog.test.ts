import { readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { BUNDLES, SHERPA_WASM_VERSION } from './asr-catalog';

describe('asr-catalog (#164)', () => {
  it('names the runtime version server/Dockerfile copies in', () => {
    const dockerfile = readFileSync(path.resolve(import.meta.dirname, '../../../../../Dockerfile'), 'utf8');
    expect(dockerfile).toContain(`convoscribe-sherpa-wasm:${SHERPA_WASM_VERSION}-`);
    expect(dockerfile).toContain(`./public/sherpa-onnx/${SHERPA_WASM_VERSION}/`);
  });

  it('has the app’s English and French Kroko bundles', () => {
    expect(BUNDLES.map((b) => b.id)).toEqual(['asr-en-kroko-2025-08-06', 'asr-fr-kroko-2025-08-06']);
  });
});
