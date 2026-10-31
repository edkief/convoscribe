import type { ReactNode } from 'react';

/**
 * LLM output (summaries, answers) as Markdown, the way the app shows it
 * (`MarkdownText`): headings, emphasis, inline and fenced code, lists,
 * quotes, rules and tables. It builds React elements, never HTML from the
 * text, and links only to http(s) addresses.
 */
export function Markdown({ text }: { text: string }) {
  return <div className="markdown">{blocks(text.replace(/\r\n?/g, '\n').split('\n'))}</div>;
}

const INLINE = /(`[^`]+`)|(\*\*[^*]+\*\*|__[^_]+__)|(\*[^*\s][^*]*\*|_[^_\s][^_]*_)|(\[[^\]]+\]\([^)\s]+\))/;

function inline(text: string): ReactNode[] {
  const out: ReactNode[] = [];
  let rest = text;
  let key = 0;
  for (let match = INLINE.exec(rest); match; match = INLINE.exec(rest)) {
    if (match.index > 0) out.push(rest.slice(0, match.index));
    const [token] = match;
    if (match[1]) out.push(<code key={key++}>{token.slice(1, -1)}</code>);
    else if (match[2]) out.push(<strong key={key++}>{inline(token.slice(2, -2))}</strong>);
    else if (match[3]) out.push(<em key={key++}>{inline(token.slice(1, -1))}</em>);
    else {
      const [, label, href] = /^\[([^\]]+)\]\(([^)\s]+)\)$/.exec(token)!;
      out.push(
        /^https?:\/\//i.test(href) ? (
          <a key={key++} href={href} rel="noopener noreferrer nofollow" target="_blank">
            {inline(label)}
          </a>
        ) : (
          inline(label)
        ),
      );
    }
    rest = rest.slice(match.index + token.length);
  }
  if (rest) out.push(rest);
  return out;
}

const cells = (row: string) =>
  row
    .trim()
    .replace(/^\|/, '')
    .replace(/\|$/, '')
    .split('|')
    .map((c) => c.trim());

const BULLET = /^\s*[-*+]\s+(.*)$/;
const ORDERED = /^\s*\d+[.)]\s+(.*)$/;
const TABLE_RULE = /^\s*\|?\s*:?-{3,}:?\s*(\|\s*:?-{3,}:?\s*)*\|?\s*$/;

function blocks(lines: string[]): ReactNode[] {
  const out: ReactNode[] = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const key = out.length;
    if (!line.trim()) {
      i++;
    } else if (/^\s*```/.test(line)) {
      const body: string[] = [];
      for (i++; i < lines.length && !/^\s*```/.test(lines[i]); i++) body.push(lines[i]);
      i++;
      out.push(
        <pre key={key}>
          <code>{body.join('\n')}</code>
        </pre>,
      );
    } else if (/^#{1,6}\s/.test(line)) {
      const level = Math.min(/^#+/.exec(line)![0].length + 2, 6);
      const Tag = `h${level}` as 'h3' | 'h4' | 'h5' | 'h6';
      out.push(<Tag key={key}>{inline(line.replace(/^#+\s+/, '').replace(/\s+#+\s*$/, ''))}</Tag>);
      i++;
    } else if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) {
      out.push(<hr key={key} />);
      i++;
    } else if (/^\s*>/.test(line)) {
      const body: string[] = [];
      for (; i < lines.length && /^\s*>/.test(lines[i]); i++) body.push(lines[i].replace(/^\s*>\s?/, ''));
      out.push(<blockquote key={key}>{blocks(body)}</blockquote>);
    } else if (line.includes('|') && TABLE_RULE.test(lines[i + 1] ?? '')) {
      const head = cells(line);
      const rows: string[][] = [];
      for (i += 2; i < lines.length && lines[i].includes('|') && lines[i].trim(); i++) rows.push(cells(lines[i]));
      out.push(
        <table key={key}>
          <thead>
            <tr>
              {head.map((c, j) => (
                <th key={j}>{inline(c)}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((row, r) => (
              <tr key={r}>
                {head.map((_, j) => (
                  <td key={j}>{inline(row[j] ?? '')}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>,
      );
    } else if (BULLET.test(line) || ORDERED.test(line)) {
      const ordered = !BULLET.test(line);
      const pattern = ordered ? ORDERED : BULLET;
      const items: string[] = [];
      for (; i < lines.length; i++) {
        const item = pattern.exec(lines[i]);
        if (item) items.push(item[1]);
        else if (lines[i].trim() && /^\s{2,}/.test(lines[i]) && items.length) items[items.length - 1] += ` ${lines[i].trim()}`;
        else break;
      }
      const List = ordered ? 'ol' : 'ul';
      out.push(
        <List key={key}>
          {items.map((item, j) => (
            <li key={j}>{inline(item)}</li>
          ))}
        </List>,
      );
    } else {
      const body: string[] = [line.trim()];
      for (
        i++;
        i < lines.length &&
        lines[i].trim() &&
        !/^(#{1,6}\s|\s*```|\s*>)/.test(lines[i]) &&
        !BULLET.test(lines[i]) &&
        !ORDERED.test(lines[i]);
        i++
      )
        body.push(lines[i].trim());
      out.push(<p key={key}>{inline(body.join(' '))}</p>);
    }
  }
  return out;
}
