/**
 * R1 — pure author matcher for the discover rails. The rail search is free text
 * over title + author + description, so criticism and biographies outrank the
 * works; `matchesAuthorQuery` is the client-side attribution guarantee. These
 * tests pin the surname-is-last-token rule, whole-word matching, accent/case
 * folding and the empty-authors rejection.
 */
import { describe, expect, it } from 'vitest';
import { matchesAuthorQuery } from '$lib/features/discover/railPlan';
import { AUTHOR_ROTATION } from '$lib/features/discover/railRotation';
import type { CatalogBook } from '$lib/shared/services/catalog';

function book(authors: string[], title = 'Title'): CatalogBook {
  return {
    id: 'googlebooks:fixture',
    provider: 'googlebooks',
    title,
    authors,
    coverUrl: null,
    languages: ['en'],
    subjects: ['Fiction'],
    downloadUrl: null,
  };
}

/** The surname the matcher checks: the last whitespace token of the term. */
function surname(term: string): string {
  return term.trim().split(/\s+/).pop() ?? '';
}

/** Realistic author credit per rotation entry, spelled as a catalog would. */
const REALISTIC_AUTHOR: Readonly<Record<string, string>> = {
  'arthur conan doyle': 'Arthur Conan Doyle',
  'edgar allan poe': 'Edgar Allan Poe',
  'oscar wilde': 'Oscar Wilde',
  'jules verne': 'Jules Verne',
  'franz kafka': 'Franz Kafka',
  'charles dickens': 'Charles Dickens',
  'jack london': 'Jack London',
  'h g wells': 'H. G. Wells',
  'mark twain': 'Mark Twain',
  'herman melville': 'Herman Melville',
};

describe('matchesAuthorQuery — accepts the rail author', () => {
  it('matches every rotation term against its realistic author credit', () => {
    for (const entry of AUTHOR_ROTATION) {
      const credit = REALISTIC_AUTHOR[entry.term];
      expect(credit, `missing fixture for ${entry.term}`).toBeTruthy();
      expect(matchesAuthorQuery(book([credit!]), entry.term)).toBe(true);
    }
  });

  it('matches the surname wherever it sits in a multi-author credit list', () => {
    expect(
      matchesAuthorQuery(book(['An Anthology Editor', 'Herman Melville']), 'herman melville'),
    ).toBe(true);
  });

  it('matches "Surname, Given" order and stray punctuation', () => {
    expect(matchesAuthorQuery(book(['Melville, Herman']), 'herman melville')).toBe(true);
    expect(matchesAuthorQuery(book(['Herman Melville.']), 'herman melville')).toBe(true);
    expect(matchesAuthorQuery(book(['Herman Melville (1819-1891)']), 'herman melville')).toBe(true);
  });
});

describe('matchesAuthorQuery — folds case and accents', () => {
  it('ignores case in both the term and the author', () => {
    expect(matchesAuthorQuery(book(['HERMAN MELVILLE']), 'herman melville')).toBe(true);
    expect(matchesAuthorQuery(book(['herman melville']), 'Herman Melville')).toBe(true);
  });

  it('ignores combining accents on either side', () => {
    expect(matchesAuthorQuery(book(['Gabriel García Márquez']), 'gabriel garcia marquez')).toBe(
      true,
    );
    expect(matchesAuthorQuery(book(['gabriel garcia marquez']), 'Gabriel García Márquez')).toBe(
      true,
    );
  });
});

describe('matchesAuthorQuery — rejects non-attribution', () => {
  it('rejects a book whose title mentions the name but whose authors do not', () => {
    const biography = book(['Hershel Parker'], 'Herman Melville: A Biography');
    expect(matchesAuthorQuery(biography, 'herman melville')).toBe(false);
    const studyGuide = book(['Bright Summaries'], 'A Study Guide to Moby-Dick');
    expect(matchesAuthorQuery(studyGuide, 'herman melville')).toBe(false);
  });

  it('rejects a book with no authors', () => {
    expect(matchesAuthorQuery(book([]), 'herman melville')).toBe(false);
  });

  it('rejects a longer surname that merely contains the whole word', () => {
    expect(matchesAuthorQuery(book(['Melvilles']), 'herman melville')).toBe(false);
    expect(matchesAuthorQuery(book(['Doyles']), 'arthur conan doyle')).toBe(false);
    expect(matchesAuthorQuery(book(['Mel']), 'herman melville')).toBe(false);
  });

  it('rejects an unrelated author even when the term has several tokens', () => {
    expect(matchesAuthorQuery(book(['Nathaniel Hawthorne']), 'herman melville')).toBe(false);
  });

  it('is total: an empty or whitespace-only term never passes', () => {
    expect(matchesAuthorQuery(book(['Herman Melville']), '')).toBe(false);
    expect(matchesAuthorQuery(book(['Herman Melville']), '   ')).toBe(false);
  });
});

describe('matchesAuthorQuery — surname selection', () => {
  it('checks the last token of the term, not an earlier one', () => {
    // "doyle" is the surname; "arthur" is a given name and must not match.
    expect(matchesAuthorQuery(book(['Arthur Miller']), 'arthur conan doyle')).toBe(false);
    expect(matchesAuthorQuery(book(['Arthur Conan Doyle']), 'arthur conan doyle')).toBe(true);
    for (const entry of AUTHOR_ROTATION) {
      expect(surname(entry.term).length).toBeGreaterThan(0);
    }
  });
});
