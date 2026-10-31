import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { highlight } from '@/lib/search-text';
import { Highlighted } from './Highlighted';

describe('Highlighted (#83)', () => {
  it('marks what matched, and keeps markup in a transcript as text', () => {
    const segments = highlight('<img src=x onerror=alert(1)> the team', ['team']);
    expect(renderToStaticMarkup(<Highlighted segments={segments} />)).toBe(
      '&lt;img src=x onerror=alert(1)&gt; the <mark>team</mark>',
    );
  });
});
