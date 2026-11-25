import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh() {} }) }));

const { Recorder } = await import('./Recorder');

describe('Recorder (#164)', () => {
  it('renders the language, the transcript switch and the record button before the browser takes over', () => {
    const html = renderToStaticMarkup(<Recorder />);
    expect(html).toContain('aria-label="Start recording"');
    expect([...html.matchAll(/<option value="(\w+)"/g)].map((m) => m[1])).toEqual(['en', 'fr']);
    expect(html).toContain('Live transcript');
    expect(html).toContain('Checking the speech model');
  });
});
