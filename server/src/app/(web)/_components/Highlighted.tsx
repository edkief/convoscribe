import type { Segment } from '@/lib/search-text';

/** Text with the words a search found marked (#83); all of it stays text, never HTML. */
export function Highlighted({ segments }: { segments: Segment[] }) {
  return segments.map((s, i) => (s.match ? <mark key={i}>{s.text}</mark> : s.text));
}
