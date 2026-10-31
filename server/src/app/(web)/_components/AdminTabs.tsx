import Link from 'next/link';

/** Between the admin pages (#82), since narrow screens hide the sidebar. */
export function AdminTabs({ current }: { current: 'users' | 'status' }) {
  return (
    <nav className="admin-tabs" aria-label="Admin">
      <Link href="/admin/users" aria-current={current === 'users' ? 'page' : undefined}>
        Users
      </Link>
      <Link href="/admin/status" aria-current={current === 'status' ? 'page' : undefined}>
        Status
      </Link>
    </nav>
  );
}
