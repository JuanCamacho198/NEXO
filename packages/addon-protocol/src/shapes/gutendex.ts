/**
 * Gutendex shape adapter. Pure, no network.
 *
 * Gutendex answers `/books` with its own pagination envelope and `/books/{id}`
 * with a single book, so its fields are mapped explicitly. Anything unreadable
 * is counted, never thrown: a shape adapter is a convenience, not a validator.
 */

import { TYPE_BOOK } from '../constants';
import type { CatalogPayload, MetaPreview } from '../payload';

const MAX_STRING_CHARS = 2048;
const GUTENBERG_ID_PREFIX = 'gutenberg:';
const COVER_FORMAT = 'image/jpeg';

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function readString(value: unknown): string | undefined {
  if (typeof value !== 'string' || value.length === 0) return undefined;
  return value.length > MAX_STRING_CHARS ? value.slice(0, MAX_STRING_CHARS) : value;
}

function readStringArray(value: unknown): string[] {
  if (!Array.isArray(value)) return [];
  const entries: string[] = [];
  for (const entry of value) {
    const text =
      typeof entry === 'string'
        ? readString(entry)
        : isPlainObject(entry)
          ? readString(entry.name)
          : undefined;
    if (text !== undefined) entries.push(text);
  }
  return entries;
}

/** Gutendex ids are integers; a numeric string is accepted as the same id. */
function readGutenbergId(value: unknown): string | undefined {
  if (typeof value === 'number' && Number.isInteger(value) && value > 0) {
    return `${GUTENBERG_ID_PREFIX}${value}`;
  }
  if (typeof value === 'string' && /^\d+$/.test(value)) {
    return `${GUTENBERG_ID_PREFIX}${value}`;
  }
  return undefined;
}

export function shapeGutendexMeta(body: unknown): MetaPreview | null {
  if (!isPlainObject(body)) return null;
  const id = readGutenbergId(body.id);
  const name = readString(body.title);
  if (id === undefined || name === undefined) return null;

  const meta: MetaPreview = {
    id,
    type: TYPE_BOOK,
    name,
    authors: readStringArray(body.authors),
    languages: readStringArray(body.languages),
    subjects: readStringArray(body.subjects),
  };
  const formats = isPlainObject(body.formats) ? body.formats : undefined;
  const poster = readString(formats?.[COVER_FORMAT]);
  if (poster !== undefined) meta.poster = poster;
  return meta;
}

export function shapeGutendexCatalog(body: unknown): CatalogPayload {
  if (!isPlainObject(body) || !Array.isArray(body.results)) {
    return { metas: [], skipped: 1 };
  }

  const metas: MetaPreview[] = [];
  let skipped = 0;
  for (const entry of body.results) {
    const meta = shapeGutendexMeta(entry);
    if (meta === null) {
      skipped += 1;
      continue;
    }
    metas.push(meta);
  }
  return { metas, skipped };
}
