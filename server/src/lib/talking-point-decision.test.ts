import { describe, expect, it } from 'vitest';
import { GROUP_SIZE, INSTRUCTIONS, MAX_STATE_CHARS, NONE_KEY, NONE_TEXT, decisionCovered, decisionQuestions, decisionStates, splitText } from './talking-point-decision';

const answer = (choice: string, p: number) => ({ choice, probabilities: { [choice]: p } });

describe('talking points by decision service (#101), as the app’s DecisionTalkingPoints', () => {
  it('makes each line a state of its own, with its time and speaker', () => {
    expect(
      decisionStates([
        { startMs: 5000, endMs: 6000, speaker: 'Alice', text: 'Budget first.' },
        { startMs: 12_000, endMs: 13_000, speaker: null, text: 'Then hiring.' },
      ]),
    ).toEqual([
      { text: '[00:05] Alice: Budget first.', atMs: 5000 },
      { text: '[00:12] Then hiring.', atMs: 12_000 },
    ]);
  });

  it('sends a long line in parts, each with the line’s time, losing nothing', () => {
    const text = 'We went through the whole budget line by line and agreed on the numbers. '.repeat(40).trim();
    const states = decisionStates([{ startMs: 65_000, endMs: 70_000, speaker: 'Bob', text }]);
    expect(states.length).toBeGreaterThan(1);
    for (const s of states) {
      expect(s.text.length).toBeLessThanOrEqual(MAX_STATE_CHARS);
      expect(s.text.startsWith('[01:05] Bob: ')).toBe(true);
      expect(s.atMs).toBe(65_000);
    }
    expect(states.map((s) => s.text.replace('[01:05] Bob: ', '')).join(' ')).toBe(text);
  });

  it('breaks at a space when there is no sentence end', () => {
    const parts = splitText('word '.repeat(300).trim(), 100);
    expect(parts.every((p) => p.length <= 100 && p === p.trim())).toBe(true);
    expect(parts.join(' ').split(' ')).toHaveLength(300);
  });

  it('spreads the open points evenly, numbered from 1, each question ending with none', () => {
    const questions = decisionQuestions(Array.from({ length: 12 }, (_, i) => `Point ${i + 1}`));
    expect(Object.keys(questions)).toEqual(['points1', 'points2', 'points3']);
    expect(Object.keys(questions.points1.options)).toEqual(['1', '4', '7', '10', NONE_KEY]);
    expect(questions.points1.options[NONE_KEY]).toBe(NONE_TEXT);
    expect(questions.points1.instructions).toBe(INSTRUCTIONS);
    expect(Object.values(decisionQuestions(['a', 'b', 'c', 'd', 'e', 'f'])).map((q) => Object.keys(q.options).length)).toEqual([4, 4]);
  });

  it('fills each question up to five with the session’s other points, numbered after the open ones', () => {
    expect(decisionQuestions(['Budget'], ['Hiring', 'Office']).points1.options).toEqual({ 1: 'Budget', 2: 'Hiring', 3: 'Office', [NONE_KEY]: NONE_TEXT });
    const many = decisionQuestions(['a', 'b', 'c', 'd', 'e', 'f'], ['x', 'y', 'z', 'w', 'v', 'u', 't']);
    expect(Object.values(many).every((q) => Object.keys(q.options).length === GROUP_SIZE + 1)).toBe(true);
    expect(decisionQuestions([], ['x'])).toEqual({});
  });

  it('covers a point at the first line where it is the sure answer, and never a settled one or none', () => {
    const states = [1000, 2000, 3000].map((atMs) => ({ text: '', atMs }));
    const answers = [
      { points1: answer('2', 0.4), points2: answer(NONE_KEY, 0.9) },
      { points1: answer('2', 0.8), points2: answer('6', 0.5) },
      { points1: answer('2', 0.99), points2: answer('9', 0.99) },
    ];
    expect(decisionCovered(states, answers, 6)).toEqual([
      { number: 2, atMs: 2000 },
      { number: 6, atMs: 2000 },
    ]);
  });
});
