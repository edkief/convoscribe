import { describe, expect, it } from 'vitest';
import { mergeActionItems, ownerName, sameItem, splitSummary, visibleSummary, type ExtractedActionItem } from './action-items';

// The cases of the app's ActionItemsParserTest and ActionItemMergeTest (#31).

const item = (text: string, extra: Partial<ExtractedActionItem> = {}): ExtractedActionItem => ({ text, owner: null, due: null, atMs: null, ...extra });

const prose = '**Overview**\nWe planned the launch.\n\n**Action items**\n- Alice: send the deck [02:10]\n- Bob books the room by 2026-11-06\n';

describe('splitSummary', () => {
  it('takes the JSON block off the prose and reads its items', () => {
    const text =
      prose +
      '\n```json\n' +
      '[{"text": "Send the deck", "owner": "alice", "due": null, "at": "02:10"},' +
      ' {"text": "Book the room", "owner": "Bob", "due": "2026-11-06", "at": null}]\n```\n';
    const parsed = splitSummary(text, ['Alice', 'Bob']);
    expect(parsed.fromJson).toBe(true);
    expect(parsed.prose).toBe(prose.trimEnd());
    expect(parsed.items).toEqual([item('Send the deck', { owner: 'Alice', atMs: 130_000 }), item('Book the room', { owner: 'Bob', due: '2026-11-06' })]);
  });

  it('reads an empty array as no items, even with an actions section', () => {
    const parsed = splitSummary(prose + '\n```json\n[]\n```');
    expect(parsed.fromJson).toBe(true);
    expect(parsed.items).toEqual([]);
    expect(parsed.prose).toBe(prose.trimEnd());
  });

  it('accepts a bare fence and lenient JSON', () => {
    const parsed = splitSummary('Summary.\n```\n[{text: "Call the bank", owner: null, at: "[1:02:03]"}]\n```');
    expect(parsed.fromJson).toBe(true);
    expect(parsed.prose).toBe('Summary.');
    expect(parsed.items).toEqual([item('Call the bank', { atMs: 3_723_000 })]);
  });

  it('accepts an object holding the array', () => {
    expect(splitSummary('Summary.\n```json\n{"action_items": [{"text": "Ship it"}]}\n```').items).toEqual([item('Ship it')]);
  });

  it('drops bad fields rather than failing', () => {
    const text = 'Summary.\n```json\n[{"text": "Ship it", "due": "next Friday", "at": "soon"}, {"owner": "Bob"}, "loose", {"text": "  "}]\n```';
    expect(splitSummary(text).items).toEqual([item('Ship it')]);
  });

  it('falls back to the bullet list for malformed JSON', () => {
    const parsed = splitSummary(prose + '\n```json\n[{"text": "Send the deck", owner: }\n```', ['Alice', 'Bob']);
    expect(parsed.fromJson).toBe(false);
    expect(parsed.prose).toBe(prose.trimEnd());
    expect(parsed.items).toEqual([
      item('send the deck', { owner: 'Alice', atMs: 130_000 }),
      item('Bob books the room by 2026-11-06', { owner: 'Bob', due: '2026-11-06' }),
    ]);
  });

  it('still takes a cut-off block off and falls back', () => {
    const parsed = splitSummary(prose + '\n```json\n[{"text": "Send the');
    expect(parsed.fromJson).toBe(false);
    expect(parsed.prose).toBe(prose.trimEnd());
    expect(parsed.items).toHaveLength(2);
  });

  it('reads only the action items section without a block', () => {
    const text = '## Key points\n- Budget is fine\n\n## Action items\n1. Dana: draft the contract\n2. Review pricing (Marc)\n\n## Notes\n- Not an item\n';
    const parsed = splitSummary(text);
    expect(parsed.fromJson).toBe(false);
    expect(parsed.prose).toBe(text.trimEnd());
    expect(parsed.items).toEqual([item('draft the contract', { owner: 'Dana' }), item('Review pricing', { owner: 'Marc' })]);
  });

  it('ends the list at a numbered section', () => {
    expect(splitSummary('4. Action items:\n- Send the deck\n5. Follow-ups:\n- Not an item\n').items).toEqual([item('Send the deck')]);
  });

  it('reads None as no items', () => {
    expect(splitSummary('4. Action items: None').items).toEqual([]);
    expect(splitSummary('**Action items**\n- None.\n').items).toEqual([]);
    expect(splitSummary('**Actions à mener**\n- Aucune\n').items).toEqual([]);
  });

  it('finds a French heading', () => {
    const text = '**Points clés**\n- Le budget\n\n**Actions à mener**\n- Élodie envoie le devis [00:45]\n';
    expect(splitSummary(text, ['Élodie']).items).toEqual([item('Élodie envoie le devis', { owner: 'Élodie', atMs: 45_000 })]);
  });

  it('finds none in a summary without actions', () => {
    const parsed = splitSummary('- Point one\n- Point two\n');
    expect(parsed.items).toEqual([]);
    expect(parsed.prose).toBe('- Point one\n- Point two');
  });

  it('does not read a clock time in a bullet as a timestamp', () => {
    expect(splitSummary('**Action items**\n- Call Bob at 14:30\n').items[0].atMs).toBeNull();
  });
});

describe('ownerName', () => {
  it('matches known names ignoring case and accents', () => {
    const names = ['Élodie Martin', 'Bob'];
    expect(ownerName('elodie martin', names)).toBe('Élodie Martin');
    expect(ownerName('Bob (sales)', names)).toBe('Bob');
    expect(ownerName('Marketing team', names)).toBe('Marketing team');
    expect(ownerName('None', names)).toBeNull();
    expect(ownerName('  ', names)).toBeNull();
  });
});

describe('visibleSummary', () => {
  it('hides the block while it streams', () => {
    const head = 'Summary text.';
    for (const tail of ['\n`', '\n``', '\n```', '\n```js', '\n```json\n[{"text": "Se', '\n```json\n[]\n```']) {
      expect(visibleSummary(head + tail)).toBe(head);
    }
    expect(visibleSummary(`${head}\n`)).toBe(`${head}\n`);
    const code = 'Run:\n```\nls\n```\nThen go.';
    expect(visibleSummary(code)).toBe(code);
  });
});

describe('sameItem', () => {
  it('ignores case, accents, punctuation and small rewordings', () => {
    expect(sameItem('Envoyer le devis à Élodie', 'envoyer le devis a elodie')).toBe(true);
    expect(sameItem('Send Bob the deck', 'Send the deck to Bob')).toBe(true);
    expect(sameItem('Send the deck', 'Send the slide deck')).toBe(true);
    expect(sameItem('Send the deck', 'Book the room')).toBe(false);
    expect(sameItem('Call Ann', 'Call Bob')).toBe(false);
  });
});

describe('mergeActionItems', () => {
  const existing = (text: string, extra: Record<string, unknown> = {}) => ({ text, extractedText: text, createdAt: 0, ...extra });

  it('adds every item of a first summary in order', () => {
    expect(mergeActionItems([], [item('Send the deck', { owner: 'Ann', due: '2026-11-06', atMs: 5 }), item('Book')], 9)).toEqual([
      { text: 'Send the deck', extractedText: 'Send the deck', owner: 'Ann', dueDate: '2026-11-06', atMs: 5, done: false, edited: false, deleted: false, createdAt: 9 },
      { text: 'Book', extractedText: 'Book', owner: null, dueDate: null, atMs: null, done: false, edited: false, deleted: false, createdAt: 9 },
    ]);
  });

  it('replaces untouched items and keeps touched ones as they are', () => {
    const before = [
      existing('Send the deck', { done: true }),
      existing('Book the room'),
      existing('Call the bank', { deleted: true }),
      existing('Draft the big contract', { extractedText: 'Draft the contract', edited: true, future: 'kept' }),
    ];
    const merged = mergeActionItems(
      before,
      ['send the deck.', 'Book the room', 'Call the bank', 'Draft the contract', 'Hire a designer', 'Hire a designer!'].map((t) => item(t)),
      9,
    );
    expect(merged.map((m) => m.text)).toEqual(['Send the deck', 'Call the bank', 'Draft the big contract', 'Book the room', 'Hire a designer']);
    expect(merged[2]).toBe(before[3]);
    expect(merged[3].createdAt).toBe(9);
  });

  it('drops only the model items for an empty new list', () => {
    expect(mergeActionItems([existing('A', { done: true }), existing('B')], [], 9).map((m) => m.text)).toEqual(['A']);
  });
});
