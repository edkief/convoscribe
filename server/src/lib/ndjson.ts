import { HttpError } from './http';
import { LlmError } from './llm-client';

/** Sends one event of the answer's stream; ignored once the request is closed. */
export type Send<E> = (event: E) => void;

/**
 * A streamed answer for the web app's language-model routes (#117, #118):
 * newline-delimited JSON events, written by `run` with `send`. Closing the
 * request aborts `signal`, so the model is cancelled and nothing more is
 * sent. A failure in `run` ends the stream with `{ error: code }`: the code of
 * an [LlmError] or [HttpError] (`conflict` when the session changed), else
 * `internal`, logged under `label`.
 */
export function ndjsonResponse<E extends object>(req: Request, label: string, run: (send: Send<E | { error: string }>, signal: AbortSignal) => Promise<void>): Response {
  const abort = new AbortController();
  req.signal.addEventListener('abort', () => abort.abort(), { once: true });
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const send = (event: E | { error: string }) => {
        if (!abort.signal.aborted) controller.enqueue(encoder.encode(JSON.stringify(event) + '\n'));
      };
      try {
        await run(send, abort.signal);
      } catch (err) {
        if (!abort.signal.aborted) {
          if (err instanceof LlmError || err instanceof HttpError) {
            send({ error: err.code });
          } else {
            console.error(`${label} failed`, err);
            send({ error: 'internal' });
          }
        }
      } finally {
        try {
          controller.close();
        } catch {
          // Already cancelled by the page.
        }
      }
    },
    cancel() {
      abort.abort();
    },
  });
  return new Response(stream, {
    headers: {
      'content-type': 'application/x-ndjson; charset=utf-8',
      // Next's gzip would hold the events back.
      'cache-control': 'no-store, no-transform',
      'x-accel-buffering': 'no',
    },
  });
}

/** A `send` for text that sends at most once per `everyMs`; `flush` sends what was held back. */
export function throttled(send: (text: string) => void, everyMs: number) {
  let latest = '';
  let sentAt = 0;
  return {
    text(text: string) {
      latest = text;
      if (Date.now() - sentAt < everyMs) return;
      sentAt = Date.now();
      send(text);
    },
    flush() {
      send(latest);
    },
  };
}
