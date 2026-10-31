'use client';

import { useEffect, useId, useState } from 'react';
import { IconChevronDown, IconDownload } from './Icons';

export type Download = { href: string; label: string; detail: string };

/** A *Download* button opening the session's files (#80); Escape or a click elsewhere closes it. */
export function DownloadMenu({ items }: { items: Download[] }) {
  const [open, setOpen] = useState(false);
  const id = useId();
  useEffect(() => {
    if (!open) return;
    const close = (event: Event) => {
      if (event instanceof KeyboardEvent && event.key !== 'Escape') return;
      if (event instanceof MouseEvent && document.getElementById(id)?.contains(event.target as Node)) return;
      setOpen(false);
    };
    window.addEventListener('mousedown', close);
    window.addEventListener('keydown', close);
    return () => {
      window.removeEventListener('mousedown', close);
      window.removeEventListener('keydown', close);
    };
  }, [open, id]);
  return (
    <div className="menu" id={id}>
      <button type="button" className="button" aria-haspopup="menu" aria-expanded={open} onClick={() => setOpen(!open)}>
        <IconDownload size={16} />
        Download
        <IconChevronDown size={16} />
      </button>
      {open && (
        <div className="menu-list" role="menu">
          {items.map((item) => (
            <a key={item.href} href={item.href} role="menuitem" download className="menu-item" onClick={() => setOpen(false)}>
              <span>{item.label}</span>
              <span className="menu-detail">{item.detail}</span>
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
