import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { Markdown } from './Markdown';

const html = (text: string) => renderToStaticMarkup(<Markdown text={text} />).replace(/^<div class="markdown">|<\/div>$/g, '');

describe('Markdown (#80)', () => {
  it('renders what summaries use', () => {
    expect(html('## Decisions\n- Ship **part 1**\n- Notes go out *Friday*\n\n1. One\n2. Two')).toBe(
      '<h4>Decisions</h4><ul><li>Ship <strong>part 1</strong></li><li>Notes go out <em>Friday</em></li></ul>' +
        '<ol><li>One</li><li>Two</li></ol>',
    );
    expect(html('| A | B |\n|---|---|\n| 1 | 2 |')).toBe(
      '<table><thead><tr><th>A</th><th>B</th></tr></thead><tbody><tr><td>1</td><td>2</td></tr></tbody></table>',
    );
    expect(html('> quoted\n\n---\n\n```\nx < y\n```\n\nuse `code`')).toBe(
      '<blockquote><p>quoted</p></blockquote><hr/><pre><code>x &lt; y</code></pre><p>use <code>code</code></p>',
    );
  });

  it('never turns text into HTML, and links only to http(s)', () => {
    expect(html('<img src=x onerror=alert(1)> **<b>**')).toBe(
      '<p>&lt;img src=x onerror=alert(1)&gt; <strong>&lt;b&gt;</strong></p>',
    );
    expect(html('[docs](https://example.com) [bad](javascript:alert(1))')).toBe(
      '<p><a href="https://example.com" rel="noopener noreferrer nofollow" target="_blank">docs</a> bad)</p>',
    );
  });

  it('leaves underscores inside words alone', () => {
    expect(html('see file_name_here and _this_')).toBe('<p>see file_name_here and <em>this</em></p>');
  });
});
