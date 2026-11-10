'use client';

import { useEffect, useMemo, useState } from 'react';
import type { CheckSetup, StatusCheck } from '@/lib/status';
import { IconAlert, IconCheckCircle, IconMinusCircle, IconSpinner, IconXCircle } from './Icons';

const LABELS: Record<StatusCheck, string> = {
  postgres: 'Postgres',
  s3: 'S3',
  'authz-proxy': 'authz-proxy',
  llm: 'Language model',
  asr: 'Transcription',
  decision: 'Decision service',
};

type RowState =
  | { kind: 'checking' }
  | { kind: 'off' }
  | { kind: 'done'; ok: boolean; latencyMs: number }
  /** The page could not ask: signed out, or the server itself away. */
  | { kind: 'failed' };

const initial = (setup: CheckSetup): RowState => (setup.configured ? { kind: 'checking' } : { kind: 'off' });

/**
 * Each dependency's reachability (#82, #145), asked for one by one once the
 * page is up, so a slow upstream holds back only its own row. Rows the
 * server is not set up with show as such without asking.
 */
export function Reachability({ checks }: { checks: CheckSetup[] }) {
  const visible = useMemo(() => checks.filter((c) => c.name !== 'authz-proxy' || c.configured), [checks]);
  const [states, setStates] = useState<Record<string, RowState>>(() => Object.fromEntries(visible.map((c) => [c.name, initial(c)])));
  const [round, setRound] = useState(0);

  // Rows start, and are reset to, checking; each fetch settles its own row.
  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    for (const c of visible) {
      if (!c.configured) continue;
      fetch(`/api/web/v1/admin/status/${c.name}`, { signal, cache: 'no-store' })
        .then(async (res): Promise<RowState> => {
          if (!res.ok) throw new Error(String(res.status));
          const { state } = (await res.json()) as { state: { ok: boolean; latencyMs: number } | null };
          return state ? { kind: 'done', ok: state.ok, latencyMs: state.latencyMs } : { kind: 'off' };
        })
        .catch((): RowState => ({ kind: 'failed' }))
        .then((next) => {
          if (!signal.aborted) setStates((s) => ({ ...s, [c.name]: next }));
        });
    }
    return () => controller.abort();
  }, [visible, round]);

  const again = () => {
    setStates(Object.fromEntries(visible.map((c) => [c.name, initial(c)])));
    setRound((r) => r + 1);
  };

  const busy = Object.values(states).some((s) => s.kind === 'checking');
  return (
    <>
      <dl className="facts" aria-live="polite" aria-busy={busy}>
        {visible.map((c) => (
          <div key={c.name}>
            <dt>
              {LABELS[c.name]}
              {c.model && <span className="reach-model">{c.model}</span>}
            </dt>
            <dd>
              <Reach state={states[c.name]} />
            </dd>
          </div>
        ))}
      </dl>
      <button type="button" className="link-button reach-again" disabled={busy} onClick={again}>
        Check again
      </button>
    </>
  );
}

function Reach({ state }: { state: RowState }) {
  switch (state.kind) {
    case 'checking':
      return (
        <span className="reach checking">
          <IconSpinner size={16} className="spin" />
          Checking…
        </span>
      );
    case 'off':
      return (
        <span className="reach off">
          <IconMinusCircle size={16} />
          Not configured
        </span>
      );
    case 'failed':
      return (
        <span className="reach failed" title="The page could not ask the server; reload to try again.">
          <IconAlert size={16} />
          Couldn’t check
        </span>
      );
    case 'done':
      return (
        <>
          <span className={`reach ${state.ok ? 'ok' : 'down'}`}>
            {state.ok ? <IconCheckCircle size={16} /> : <IconXCircle size={16} />}
            {state.ok ? 'Reachable' : 'Unreachable'}
          </span>
          <span className="reach-time">{state.latencyMs} ms</span>
        </>
      );
  }
}
