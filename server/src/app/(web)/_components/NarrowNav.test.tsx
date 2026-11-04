import { renderToStaticMarkup } from 'react-dom/server';
import { beforeEach, describe, expect, it, vi } from 'vitest';

let path = '/';
vi.mock('next/navigation', () => ({ usePathname: () => path }));

const { NarrowNav } = await import('./NarrowNav');

/** Each link's label, `*` marking the current one. */
const links = (isAdmin: boolean) =>
  [...renderToStaticMarkup(<NarrowNav isAdmin={isAdmin} />).matchAll(/<a [^>]*>/g)].map(([tag]) => {
    const label = /aria-label="([^"]+)"/.exec(tag)?.[1];
    return tag.includes('aria-current="page"') ? `${label}*` : label;
  });

describe('NarrowNav (#110)', () => {
  beforeEach(() => {
    path = '/';
  });

  it('links to talking points, search and the trash, and admin pages only for admins', () => {
    expect(links(false)).toEqual(['Talking points', 'Search', 'Trash']);
    expect(links(true)).toEqual(['Talking points', 'Search', 'Admin', 'Trash']);
  });

  it('marks the current page', () => {
    path = '/talking-points';
    expect(links(true)).toEqual(['Talking points*', 'Search', 'Admin', 'Trash']);
    path = '/admin/users';
    expect(links(true)).toEqual(['Talking points', 'Search', 'Admin*', 'Trash']);
  });
});
