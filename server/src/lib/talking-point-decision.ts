import type { DecisionAnswer, DecisionQuestion } from './decision-client';
import { lineText, type PromptLine } from './summary-prompt';

/*
 * How a talking-point check asks a laya decision service (#101), as the
 * app's `DecisionTalkingPoints` does: every transcript line is a state of
 * its own, the open points go in `choice` questions of GROUP_SIZE each plus
 * "none of these", filled up with the session's other points, and a point is
 * covered at the first line where it is the answer with at least
 * MIN_PROBABILITY. See the app's class for why it is asked this way.
 */

export const INSTRUCTIONS = 'Which talking point is this part of the conversation about?';
export const NONE_KEY = 'none';
export const NONE_TEXT = 'none of these: something else, small talk or logistics';
export const GROUP_SIZE = 5;
export const MIN_PROBABILITY = 0.5;
export const MAX_STATE_CHARS = 1000;

/** One state sent: a line, or part of a long one, and when that line starts. */
export type DecisionState = { text: string; atMs: number };

/** The states for `lines`, in order: one per line, or several for a line over MAX_STATE_CHARS. */
export function decisionStates(lines: PromptLine[]): DecisionState[] {
  return lines.flatMap((line) => {
    const whole = lineText(line);
    if (whole.length <= MAX_STATE_CHARS) return [{ text: whole, atMs: line.startMs }];
    const room = Math.max(MAX_STATE_CHARS - (whole.length - line.text.length), MAX_STATE_CHARS / 2);
    return splitText(line.text, room).map((part) => ({ text: lineText({ ...line, text: part }), atMs: line.startMs }));
  });
}

/**
 * Questions about the `open` points (numbered from 1), spread evenly over as
 * few questions as hold GROUP_SIZE each, each filled up to GROUP_SIZE with
 * `others` (numbered after the open ones, taken in turn) and ending with
 * NONE_KEY. Only numbers up to `open.length` can be ticked.
 */
export function decisionQuestions(open: string[], others: string[] = []): Record<string, DecisionQuestion> {
  const questions: Record<string, DecisionQuestion> = {};
  if (open.length === 0) return questions;
  const groups = Math.ceil(open.length / GROUP_SIZE);
  let nextOther = 0;
  for (let group = 0; group < groups; group++) {
    const options: Record<string, string> = {};
    open.forEach((point, i) => {
      if (i % groups === group) options[String(i + 1)] = point;
    });
    const fill = Math.min(GROUP_SIZE - Object.keys(options).length, others.length);
    for (let k = 0; k < fill; k++) {
      const j = nextOther++ % others.length;
      options[String(open.length + j + 1)] = others[j];
    }
    options[NONE_KEY] = NONE_TEXT;
    questions[`points${group + 1}`] = { instructions: INSTRUCTIONS, options };
  }
  return questions;
}

/** The points (numbered from 1, of `count`) the answers cover: each at the first state where it was the sure answer. */
export function decisionCovered(states: DecisionState[], answers: Record<string, DecisionAnswer>[], count: number): { number: number; atMs: number }[] {
  const first = new Map<number, number>();
  states.forEach((state, i) => {
    for (const answer of Object.values(answers[i] ?? {})) {
      if (!/^\d+$/.test(answer.choice)) continue;
      const number = Number(answer.choice);
      if (number < 1 || number > count || first.has(number)) continue;
      if ((answer.probabilities[answer.choice] ?? 0) >= MIN_PROBABILITY) first.set(number, state.atMs);
    }
  });
  return [...first.entries()].sort((a, b) => a[0] - b[0]).map(([number, atMs]) => ({ number, atMs }));
}

/** `text` in parts of at most `max` characters, broken after a sentence or at a space where possible. */
export function splitText(text: string, max: number): string[] {
  const parts: string[] = [];
  let rest = text.trim();
  while (rest.length > max) {
    const window = rest.slice(0, max + 1);
    let sentence = -1;
    for (const m of window.matchAll(/[.!?…](?=\s)/g)) sentence = m.index + 1;
    const space = window.lastIndexOf(' ');
    const cut = sentence > max / 2 ? sentence : space > max / 2 ? space : max;
    parts.push(rest.slice(0, cut).trim());
    rest = rest.slice(cut).trim();
  }
  if (rest) parts.push(rest);
  return parts;
}
