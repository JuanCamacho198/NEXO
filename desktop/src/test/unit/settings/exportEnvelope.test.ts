import { describe, it, expect } from 'vitest';
import {
  EXPORT_SCHEMA_VERSION,
  buildExportEnvelope,
  parseExportEnvelope,
  projectBookForExport,
} from '$lib/features/settings/exportEnvelope';
import type { HighlightDto, LibraryBookDto } from '$lib/shared/types';

const book: LibraryBookDto = {
  id: 'b1',
  title: 'Dune',
  author: 'Frank Herbert',
  format: 'epub',
  currentPage: 42,
  totalPages: 412,
  progressPercentage: 10.2,
  coverPath: '/covers/dune.jpg',
  minutesRead: 120,
  updatedAt: '2026-09-01T10:00:00Z',
  createdAt: '2026-08-01T10:00:00Z',
  collectionIds: [1],
  genre: 'sci-fi',
  language: 'en',
  coverUserDeleted: false,
  readingStatus: 'reading',
  stateVersion: 3,
};

const annotation: HighlightDto = {
  id: 'h1',
  bookId: 'b1',
  text: 'A line',
  color: 'yellow',
  pageNumber: 3,
  note: 'my note',
  createdAt: '2026-10-03T00:00:00Z',
  updatedAt: '2026-10-03T00:00:00Z',
  cfi: '/6/4',
};

describe('exportEnvelope', () => {
  it('pins the schema to the named constant and records app + modules', () => {
    const envelope = buildExportEnvelope(
      { books: [projectBookForExport(book)] },
      { exportedAt: '2026-10-04T00:00:00Z', app: '9.9.9' },
    );

    expect(envelope.manifest).toEqual({
      schema: EXPORT_SCHEMA_VERSION,
      exportedAt: '2026-10-04T00:00:00Z',
      app: '9.9.9',
      modules: ['books'],
    });
    expect(EXPORT_SCHEMA_VERSION).toBe(1);
  });

  it('lists only the populated modules, in canonical order', () => {
    const envelope = buildExportEnvelope({
      annotations: [annotation],
      books: [projectBookForExport(book)],
    });
    expect(envelope.manifest.modules).toEqual(['books', 'annotations']);
  });

  it('registers collections last in the canonical module order', () => {
    const envelope = buildExportEnvelope({
      collections: [
        {
          id: 1,
          name: 'Favorites',
          color: null,
          isSystem: true,
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      books: [projectBookForExport(book)],
      annotations: [annotation],
    });
    expect(envelope.manifest.modules).toEqual(['books', 'annotations', 'collections']);
    expect(envelope.collections).toHaveLength(1);
    expect(envelope.manifest.schema).toBe(EXPORT_SCHEMA_VERSION);
  });

  it('omits a module key that carries no data', () => {
    const envelope = buildExportEnvelope({ annotations: [] });
    // An empty array is still a populated module; the key is present.
    expect(envelope.manifest.modules).toEqual(['annotations']);
    expect(envelope.books).toBeUndefined();
  });

  it('parses a granular and a combined envelope with the same parser', () => {
    const granular = parseExportEnvelope(
      JSON.stringify(buildExportEnvelope({ annotations: [annotation] })),
    );
    const combined = parseExportEnvelope(
      JSON.stringify(
        buildExportEnvelope({ books: [projectBookForExport(book)], annotations: [annotation] }),
      ),
    );

    // Same shape; only manifest.modules and the populated keys differ.
    expect(granular.manifest.schema).toBe(combined.manifest.schema);
    expect(Object.keys(granular).sort()).toEqual(['annotations', 'manifest']);
    expect(Object.keys(combined).sort()).toEqual(['annotations', 'books', 'manifest']);
    expect(combined.books).toHaveLength(1);
    expect(combined.annotations).toEqual([annotation]);
  });

  it('exports every LibraryBookDto field except the two excluded ones', () => {
    const projected = projectBookForExport(book);
    const keys = Object.keys(projected).sort();

    expect(keys).toEqual(
      [
        'author',
        'collectionIds',
        'completedAt',
        'createdAt',
        'currentPage',
        'format',
        'genre',
        'id',
        'language',
        'minutesRead',
        'progressPercentage',
        'progressUpdatedAt',
        'publicationDate',
        'readingStatus',
        'startedAt',
        'stateVersion',
        'title',
        'totalPages',
        'updatedAt',
      ].sort(),
    );
    expect(projected).not.toHaveProperty('coverPath');
    expect(projected).not.toHaveProperty('coverUserDeleted');
  });

  it('rejects a payload that is not an envelope', () => {
    expect(() => parseExportEnvelope('{"books":[]}')).toThrow();
  });
});
