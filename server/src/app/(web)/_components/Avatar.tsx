import { initials } from '@/lib/format';

/** How many speaker colours web.css defines. */
export const TONES = 8;

/** A speaker's initials in their colour; `tone` is the speaker's position in the session. */
export function Avatar({ name, tone, size = 'md' }: { name: string; tone: number; size?: 'sm' | 'md' }) {
  return (
    <span className={`avatar ${size}`} data-tone={tone % TONES} title={name}>
      {initials(name)}
    </span>
  );
}

/** The first few speakers of a session as overlapping avatars, then how many more. */
export function AvatarStack({ names, max = 3 }: { names: string[]; max?: number }) {
  if (names.length === 0) return null;
  const shown = names.slice(0, max);
  return (
    <span className="avatar-stack" title={names.join(', ')} aria-label={`Speakers: ${names.join(', ')}`}>
      {shown.map((name, i) => (
        <Avatar key={i} name={name} tone={i} size="sm" />
      ))}
      {names.length > max && <span className="avatar sm more">+{names.length - max}</span>}
    </span>
  );
}
