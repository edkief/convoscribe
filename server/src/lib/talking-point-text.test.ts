import { describe, expect, it } from 'vitest';
import { importIntoRows, isMultiLine, pastedPoints, pasteIntoRow, pointsFromPaste, POINT_MAX } from './talking-point-text';

/** The same cases as the app's `TalkingPointListRepositoryTest.aPastedBlockSplitsIntoPoints` (#114). */
describe('pointsFromPaste (#114)', () => {
  it('splits a pasted block as the app does', () => {
    const pasted =
      '- Budget\r\n* Hiring\n\n+ Office move\r• Parking\n1. Q3 plan\n2) Holidays\n' +
      '- [ ] Open item\n[x] Done item\n-5 degrees\n3.5% growth\n  budget  \n· Réunion\nreunion';
    expect(pointsFromPaste(pasted)).toEqual([
      'Budget',
      'Hiring',
      'Office move',
      'Parking',
      'Q3 plan',
      'Holidays',
      'Open item',
      'Done item',
      '-5 degrees',
      '3.5% growth',
      'Réunion',
    ]);
    expect(pastedPoints(['Budget', 'Office'], 'budget\n- Office\nHiring')).toEqual(['Hiring']);
    expect(pointsFromPaste(`${'x'.repeat(POINT_MAX + 5)}\nb`).map((p) => p.length)).toEqual([POINT_MAX, 1]);
    expect(pointsFromPaste('\n - \n\n')).toEqual([]);
    expect(isMultiLine('a\rb')).toBe(true);
    expect(isMultiLine('a b')).toBe(false);
  });
});

describe('pasting into the list form (#114)', () => {
  it('splits lines pasted into a row after it, leaving out what other rows have', () => {
    const rows = ['Budget', '', 'Office'];
    expect(pasteIntoRow(rows, 1, '- Hiring\n- budget\n- Parking\n- office')).toEqual(['Budget', 'Hiring', 'Parking', 'Office']);
    expect(pasteIntoRow(rows, 1, 'Budget\nOffice')).toEqual(['Budget', '', 'Office']);
  });

  it('imports a block after the points, filling blank rows at the end', () => {
    expect(importIntoRows(['Budget', '', ''], '1. Hiring\n2. Budget\n3. Office')).toEqual(['Budget', 'Hiring', 'Office']);
    expect(importIntoRows(['Budget', ''], 'budget')).toEqual(['Budget']);
    expect(importIntoRows([''], 'A\nB')).toEqual(['A', 'B']);
    expect(importIntoRows([''], '\n')).toEqual(['']);
  });
});
