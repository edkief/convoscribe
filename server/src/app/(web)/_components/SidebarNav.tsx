'use client';

import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { IconLibrary, IconTag } from './Icons';

type Props = { total: number; tags: { tag: string; count: number }[] };

/** The sidebar's links (#80), the current one highlighted. */
export function SidebarNav({ total, tags }: Props) {
  const path = usePathname();
  const current = useSearchParams().get('tag');
  const onLibrary = path === '/';
  return (
    <nav className="side-nav" aria-label="Library">
      <Link href="/" className="side-link" aria-current={onLibrary && !current ? 'page' : undefined}>
        <IconLibrary />
        <span>All sessions</span>
        <span className="count">{total}</span>
      </Link>
      {tags.length > 0 && (
        <>
          <p className="side-heading">Tags</p>
          <ul>
            {tags.map(({ tag, count }) => (
              <li key={tag}>
                <Link
                  href={`/?tag=${encodeURIComponent(tag)}`}
                  className="side-link"
                  aria-current={onLibrary && current === tag ? 'page' : undefined}
                >
                  <IconTag />
                  <span className="label">{tag}</span>
                  <span className="count">{count}</span>
                </Link>
              </li>
            ))}
          </ul>
        </>
      )}
    </nav>
  );
}
