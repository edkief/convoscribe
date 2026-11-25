import { RUNTIME_BASE, RUNTIME_FILES, type AsrBundle } from './asr-catalog';

/**
 * Speech models for the Record page (#164), kept by the browser in the
 * Cache API: the sherpa-onnx runtime from this server and a language's
 * model files from Hugging Face, as the phone downloads them. Downloaded
 * once, on request; the recognizer worker reads only from here, so
 * recording never waits on, or starts, a download.
 */
export const CACHE_NAME = 'convoscribe-asr';

/** The runtime's files as absolute URLs, the cache's keys. */
export function runtimeUrls(origin = self.location.origin) {
  return Object.fromEntries(
    Object.entries(RUNTIME_FILES).map(([role, file]) => [role, new URL(`${RUNTIME_BASE}${file}`, origin).href]),
  ) as Record<keyof typeof RUNTIME_FILES, string>;
}

/** Whether the runtime and every file of `bundle` are stored. */
export async function bundleReady(bundle: AsrBundle): Promise<boolean> {
  if (typeof caches === 'undefined') return false;
  const cache = await caches.open(CACHE_NAME);
  const urls = [...Object.values(runtimeUrls()), ...bundle.files.map((f) => f.url)];
  const found = await Promise.all(urls.map((url) => cache.match(url)));
  return found.every(Boolean);
}

/** A stored file's bytes, or null. */
export async function cachedBytes(url: string): Promise<ArrayBuffer | null> {
  const res = await (await caches.open(CACHE_NAME)).match(url);
  return res ? res.arrayBuffer() : null;
}

async function fetchInto(
  cache: Cache,
  url: string,
  expected: number | null,
  onBytes: (n: number) => void,
  signal: AbortSignal,
): Promise<void> {
  const res = await fetch(url, { signal, cache: 'no-store' });
  if (!res.ok || !res.body) throw new Error(`Download failed (${res.status}): ${url}`);
  const chunks: Uint8Array[] = [];
  let size = 0;
  const reader = res.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    chunks.push(value);
    size += value.length;
    onBytes(value.length);
  }
  if (expected !== null && size !== expected) throw new Error(`Download incomplete: ${url}`);
  if (size === 0) throw new Error(`Download empty: ${url}`);
  const type = res.headers.get('content-type') ?? 'application/octet-stream';
  await cache.put(url, new Response(new Blob(chunks as BlobPart[]), { headers: { 'content-type': type } }));
}

/**
 * Download what `bundle` needs that is not stored yet, reporting bytes as
 * they arrive. Asks the browser to keep this site's storage, so the models
 * and queued recordings are not evicted. Runtimes of other versions are
 * dropped.
 */
export async function downloadBundle(bundle: AsrBundle, onBytes: (n: number) => void, signal: AbortSignal): Promise<void> {
  await navigator.storage?.persist?.().catch(() => false);
  const cache = await caches.open(CACHE_NAME);
  const runtime = Object.values(runtimeUrls());
  for (const request of await cache.keys()) {
    if (request.url.includes('/sherpa-onnx/') && !runtime.includes(request.url)) await cache.delete(request);
  }
  const wanted: { url: string; size: number | null }[] = [
    ...runtime.map((url) => ({ url, size: null })),
    ...bundle.files.map((f) => ({ url: f.url, size: f.size })),
  ];
  for (const { url, size } of wanted) {
    if (await cache.match(url)) {
      if (size) onBytes(size);
      continue;
    }
    await fetchInto(cache, url, size, onBytes, signal);
  }
}

/** Delete `bundle`'s model files; the runtime goes too once no language is left. */
export async function deleteBundle(bundle: AsrBundle, others: AsrBundle[]): Promise<void> {
  const cache = await caches.open(CACHE_NAME);
  await Promise.all(bundle.files.map((f) => cache.delete(f.url)));
  const othersLeft = await Promise.all(others.map((b) => bundleReady(b)));
  if (!othersLeft.some(Boolean)) await Promise.all(Object.values(runtimeUrls()).map((url) => cache.delete(url)));
}
