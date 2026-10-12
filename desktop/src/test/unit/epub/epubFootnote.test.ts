import { describe, expect, it } from 'vitest';
import { resolveFootnoteNote } from '$lib/features/reader/viewer-epub/epubViewerHelpers';

function docOf(body: string): Document {
  return new DOMParser().parseFromString(
    `<!DOCTYPE html><html><head></head><body>${body}</body></html>`,
    'text/html',
  );
}

describe('resolveFootnoteNote (U5)', () => {
  it('returns null for non-fragment, empty, or missing targets', () => {
    const doc = docOf('<p id="n1">Note one</p>');
    expect(resolveFootnoteNote(null, doc)).toBeNull();
    expect(resolveFootnoteNote('', doc)).toBeNull();
    expect(resolveFootnoteNote('#', doc)).toBeNull();
    expect(resolveFootnoteNote('chapter2.xhtml#n1', doc)).toBeNull();
    expect(resolveFootnoteNote('#missing', doc)).toBeNull();
  });

  it('resolves by id and collapses whitespace', () => {
    const doc = docOf('<li id="fn3">  Hello\n   world  </li>');
    expect(resolveFootnoteNote('#fn3', doc)).toEqual({ id: 'fn3', text: 'Hello world' });
  });

  it('returns null for targets without text', () => {
    const doc = docOf('<span id="empty">   </span>');
    expect(resolveFootnoteNote('#empty', doc)).toBeNull();
  });

  it('caps long notes at 600 chars', () => {
    const doc = docOf(`<p id="long">${'x'.repeat(900)}</p>`);
    const note = resolveFootnoteNote('#long', doc);
    expect(note?.text.length).toBe(600);
  });
});
