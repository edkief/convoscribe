/**
 * Read a streamed answer from the web app's language-model routes (#117,
 * #118): `onEvent` gets each newline-delimited JSON event in order. A request
 * that could not start answers plain JSON instead, whose `error` is returned;
 * otherwise null once the stream ends.
 */
export async function readEvents<E>(res: Response, onEvent: (event: E) => void): Promise<string | null> {
  if (!res.ok || !res.body) return ((await res.json().catch(() => ({}))) as { error?: string }).error ?? 'unknown';
  const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
  let buffered = '';
  for (;;) {
    const { value, done } = await reader.read();
    if (done) return null;
    buffered += value;
    let newline;
    while ((newline = buffered.indexOf('\n')) >= 0) {
      const line = buffered.slice(0, newline);
      buffered = buffered.slice(newline + 1);
      if (line.trim()) onEvent(JSON.parse(line) as E);
    }
  }
}
