/**
 * Frozen protocol values. Never change after 3.0.0: id prefixes are persisted
 * in `books.source_item_id`, so editing one is a three-database migration.
 */

export const TYPE_BOOK = 'book';

/** Declared and rejected at install; implemented in a later protocol revision. */
export const RESERVED_TYPES = ['audiobook', 'comic'] as const;

/** Frozen id prefixes understood by book provenance. */
export const ID_PREFIXES = [
  'gutenberg:',
  'isbn:',
  'openlibrary:',
  'googlebooks:',
  'archive:',
  'standardebooks:',
  'librivox:',
] as const;

/** Extra-argument names a catalog may declare. */
export const EXTRA_NAMES = ['search', 'skip', 'genre', 'lang'] as const;

/**
 * Client-bundled payload adapters. Shapes are explicitly not portable: they are
 * the price of supporting third-party APIs that will never speak the protocol.
 */
export const SHAPES = ['raw', 'gutendex', 'openlibrary', 'legacy-v2'] as const;

/** `nexo` is NEXO-only, and is capped so it can never bloat the manifest. */
export const MAX_NEXO_CHARS = 8 * 1024;
