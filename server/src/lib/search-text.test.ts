import { describe, expect, it } from 'vitest';
import { chunks, cleanText, excerpt, fold, highlight, plainText, terms, toTsQuery, words } from './search-text';

const marked = (segments: { text: string; match: boolean }[]) => segments.map((s) => (s.match ? `[${s.text}]` : s.text)).join('');

describe('search text (#83)', () => {
  it('folds case and accents, as the app does', () => {
    expect(fold('RÉUNION')).toBe('reunion');
    expect(fold('Élodie')).toBe('elodie');
    expect(fold('İstanbul')).toBe('istanbul');
  });

  it('splits words on anything but letters and digits, without repeats', () => {
    expect(words("L'équipe, l'équipe: 3.5 example.com")).toEqual(['l', 'equipe', '3', '5', 'example', 'com']);
    expect(words('会議 привет')).toEqual(['会議', 'привет']);
  });

  it('does not split a word at a decomposed accent', () => {
    expect(words('réunion')).toEqual(['reunion']);
    expect(cleanText(' réunion\u0000 \n  ok ')).toBe('réunion ok');
  });

  it('leaves out words too long to index', () => {
    expect(words(`${'a'.repeat(256)} ok`)).toEqual(['ok']);
  });

  it('lets no operator through a query', () => {
    expect(toTsQuery(terms(`bud'get & !team | (x):* <-> "y"`))).toBe(`'bud':* & 'get':* & 'team':* & 'x':* & 'y':*`);
    expect(terms(`' & | ! :*`)).toEqual([]);
  });

  it('caps a query', () => {
    expect(terms(Array.from({ length: 40 }, (_, i) => `w${i}`).join(' '))).toHaveLength(16);
    expect(terms('x'.repeat(1000))[0]).toHaveLength(200);
  });

  it('marks whole words by prefix, ignoring case and accents', () => {
    expect(marked(highlight("La Réunion, l'été; reuni", ['reun', 'ete']))).toBe("La [Réunion], l'[été]; [reuni]");
    expect(marked(highlight('nothing here', ['reun']))).toBe('nothing here');
  });

  it('keeps markup as text in segments', () => {
    expect(highlight('<b>team</b>', ['team'])).toEqual([
      { text: '<b>', match: false },
      { text: 'team', match: true },
      { text: '</b>', match: false },
    ]);
  });

  it('shows short text whole and long text around its first match', () => {
    expect(marked(excerpt('a short line about budgets', ['budget']))).toBe('a short line about [budgets]');
    const long = `${'before '.repeat(100)}budget ${'after '.repeat(100)}`.trim();
    const text = marked(excerpt(long, ['budget']));
    expect(text).toMatch(/^…before /);
    expect(text).toContain('[budget]');
    expect(text).toMatch(/ after…$/);
    expect(text.length).toBeLessThan(300);
  });

  it('cuts long text at spaces, losing nothing', () => {
    const text = Array.from({ length: 5000 }, (_, i) => `w${i}`).join(' ');
    const pieces = chunks(text, 1000);
    expect(pieces.every((p) => p.length <= 1000)).toBe(true);
    expect(pieces.join(' ')).toBe(text);
    expect(chunks('')).toEqual([]);
  });

  it('cuts text without spaces, never inside a surrogate pair', () => {
    const pieces = chunks('😀'.repeat(10), 5);
    expect(pieces.join('')).toBe('😀'.repeat(10));
    expect(pieces.every((p) => p.length <= 5 && !/^[\udc00-\udfff]|[\ud800-\udbff]$/.test(p))).toBe(true);
  });

  it('reads Markdown as plain text, keeping heading words when asked', () => {
    const markdown = '## Overview\nThe **team** agreed on [the plan](https://x.y).\n\n| A |\n|---|\n```\n- Ship it';
    expect(plainText(markdown)).toBe('The team agreed on the plan. A Ship it');
    expect(plainText(markdown, { headings: true })).toBe('Overview The team agreed on the plan. A Ship it');
  });
});
