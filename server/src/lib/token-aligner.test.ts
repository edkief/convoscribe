import { describe, expect, it } from 'vitest';
import cases from './token-aligner.cases.json';
import { align, clean, editText } from './token-aligner';

// The same cases are asserted against the app's Kotlin by its TokenAlignerCasesTest.
describe('the app’s text edit rules (#84)', () => {
  it.each(cases.align)('align: $name', ({ oldTokens, newText, startMs, endMs, expected }) => {
    expect(align(oldTokens, newText, startMs, endMs)).toEqual(expected);
  });

  it.each(cases.clean)('clean: $input', ({ input, expected }) => {
    expect(clean(input)).toBe(expected);
  });

  it.each(cases.editText)('editText: $name', ({ line, text, expected }) => {
    expect(editText(line, text)).toEqual(expected);
  });

  it('never goes backwards in time', () => {
    for (const { expected } of cases.align) {
      const times = expected.map((t) => t.ms);
      expect(times).toEqual([...times].sort((a, b) => a - b));
    }
  });
});
