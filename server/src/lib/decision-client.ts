import { fetch as undiciFetch } from 'undici';
import { agent, upstreamOf } from './ai-proxy';
import { env } from './env';
import { LlmError } from './llm-client';

/** A `choice` question for the decision service (#101): the text it answers, and its options as label to description. */
export type DecisionQuestion = { instructions: string; options: Record<string, string> };

/** The decision service's answer to a question: the option chosen, and each option's probability. */
export type DecisionAnswer = { choice: string; probabilities: Record<string, number> };

/** Every question asked of each state, in order: one record of answers per state. */
export type Decide = (states: string[], questions: Record<string, DecisionQuestion>, signal?: AbortSignal) => Promise<Record<string, DecisionAnswer>[]>;

/**
 * Whether the web app's talking-point check goes to the decision service
 * rather than the language model: only when the server has one and
 * `DECISION_FOR_TALKING_POINTS` is on (#136), since laya as shipped ticks
 * points before they come up on real recordings.
 */
export function talkingPointsByDecision(): boolean {
  return env().DECISION_FOR_TALKING_POINTS === 'on' && !!upstreamOf('decision');
}

/** States per call: laya-serve's batch endpoint takes at most 64. */
export const MAX_STATES = 64;

/**
 * Ask the server's decision service (`DECISION_UPSTREAM_*`, a laya-serve
 * 0.4.22 or later) every one of `questions` about each of `states`, through
 * `POST /v1/systemone/batch`, at most [MAX_STATES] states a call, as the
 * app's `HttpDecisionClient` does. Aborting `signal` cancels the call.
 * Failures throw an [LlmError] with the proxy's codes, the detail only in
 * the log.
 */
export const decide: Decide = async (states, questions, signal) => {
  const upstream = upstreamOf('decision');
  if (!upstream) throw new LlmError('not_offered');
  const out: Record<string, DecisionAnswer>[] = [];
  for (let i = 0; i < states.length; i += MAX_STATES) {
    out.push(...(await batch(upstream, states.slice(i, i + MAX_STATES), questions, signal)));
  }
  return out;
};

async function batch(
  upstream: NonNullable<ReturnType<typeof upstreamOf>>,
  states: string[],
  questions: Record<string, DecisionQuestion>,
  signal?: AbortSignal,
): Promise<Record<string, DecisionAnswer>[]> {
  const body = {
    states,
    questions: Object.fromEntries(
      Object.entries(questions).map(([id, q]) => [id, { type: 'choice', instructions: q.instructions, criteria: q.options }]),
    ),
    ...(upstream.model ? { model: upstream.model } : {}),
  };
  const headers: Record<string, string> = { 'content-type': 'application/json', accept: 'application/json', 'accept-encoding': 'identity' };
  if (upstream.key) headers.authorization = `Bearer ${upstream.key}`;
  let res;
  try {
    res = await undiciFetch(`${upstream.url}/v1/systemone/batch`, {
      method: 'POST',
      headers,
      body: JSON.stringify(body),
      signal,
      redirect: 'manual',
      dispatcher: agent(),
    });
  } catch (err) {
    if (signal?.aborted) throw err;
    console.warn(`ai proxy: web decision: upstream unreachable: ${err instanceof Error ? err.message : String(err)}`);
    throw new LlmError('upstream_unreachable');
  }
  if (res.status === 401 || res.status === 403) {
    await res.body?.cancel();
    console.warn(`ai proxy: web decision: upstream rejected the request (${res.status}); check DECISION_UPSTREAM_KEY`);
    throw new LlmError('upstream_rejected');
  }
  const text = await res.text().catch(() => '');
  if (!res.ok) {
    const hint = res.status === 404 ? ' (needs laya-serve 0.4.22 or later)' : '';
    console.warn(`ai proxy: web decision: upstream answered ${res.status}${hint}: ${text.slice(0, 500)}`);
    throw new LlmError('upstream_error');
  }
  const results = parseResults(text, Object.keys(questions));
  if (!results || results.length !== states.length) {
    console.warn(`ai proxy: web decision: unexpected answer: ${text.slice(0, 500)}`);
    throw new LlmError('upstream_error');
  }
  return results;
}

/** The answers in a batch reply, one record per state; null when it is not one. */
export function parseResults(text: string, ids: string[]): Record<string, DecisionAnswer>[] | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  const results = (parsed as { results?: unknown } | null)?.results;
  if (!Array.isArray(results)) return null;
  const out: Record<string, DecisionAnswer>[] = [];
  for (const result of results) {
    const r = (result ?? {}) as { answers?: Record<string, unknown>; usage?: { truncated?: unknown; state_tokens_dropped?: unknown } };
    if (r.usage?.truncated === true) console.warn(`web decision: state cut by the service, dropped=${String(r.usage.state_tokens_dropped)}`);
    const answers: Record<string, DecisionAnswer> = {};
    for (const id of ids) {
      const a = (r.answers?.[id] ?? null) as { choice?: unknown; probabilities?: unknown } | null;
      if (!a || typeof a.choice !== 'string' || !a.probabilities || typeof a.probabilities !== 'object') return null;
      const probabilities = Object.fromEntries(
        Object.entries(a.probabilities as Record<string, unknown>).filter((e): e is [string, number] => typeof e[1] === 'number'),
      );
      answers[id] = { choice: a.choice, probabilities };
    }
    out.push(answers);
  }
  return out;
}
