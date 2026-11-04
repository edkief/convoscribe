'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import type { ReactNode } from 'react';
import { IconActivity, IconChecklist, IconSearch, IconTrash } from './Icons';

/**
 * The top bar's links on narrow screens, where the sidebar's are hidden:
 * talking-point lists (#110), search, the admin pages and the trash, the
 * current one highlighted.
 */
export function NarrowNav({ isAdmin }: { isAdmin: boolean }) {
  const path = usePathname();
  const link = (href: string, label: string, icon: ReactNode, current = path === href) => (
    <Link href={href} className="icon-button narrow-only" title={label} aria-label={label} aria-current={current ? 'page' : undefined}>
      {icon}
    </Link>
  );
  return (
    <>
      {link('/talking-points', 'Talking points', <IconChecklist />)}
      {link('/search', 'Search', <IconSearch />)}
      {isAdmin && link('/admin/status', 'Admin', <IconActivity />, path.startsWith('/admin'))}
      {link('/trash', 'Trash', <IconTrash />)}
    </>
  );
}
