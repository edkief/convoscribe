'use client';

import Link from 'next/link';
import { usePathname, useSearchParams } from 'next/navigation';
import { IconLibrary, IconTag, IconTrash } from './Icons';

type Props = { total: number; tags: { tag: string; count: number }[]; trash: number };

/** The sidebar's links (#80) and the trash (#81), the current one highlighted. */
export function SidebarNav({ total, tags, trash }: Props) {
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
      <Link href="/trash" className="side-link" aria-current={path === '/trash' ? 'page' : undefined}>
        <IconTrash />
        <span>Trash</span>
        {trash > 0 && <span className="count">{trash}</span>}
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
