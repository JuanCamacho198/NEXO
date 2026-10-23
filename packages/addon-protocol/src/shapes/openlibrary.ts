/**
 * Open Library shape adapter. Pure, no network.
 *
 * Search (`/search.json`) answers with `{ docs }`, a work (`/works/{id}.json`)
 * with a single object. Ids are derived from the work key, `isbn13` from the
 * ISBN list, and the cover from the cover id — no extra HTTP call.
 */

import { TYPE_BOOK } from '../constants';
import type { CatalogPayload, MetaPreview } from '../payload';

const MAX_STRING_CHARS = 2048;
const OPENLIBRARY_ID_PREFIX = 'openlibrary:';
const COVER_URL_PREFIX = 'https://covers.openlibrary.org/b/id/';
const COVER_URL_SUFFIX = '-M.jpg';

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

/** `/works/OL66554W` and `OL66554W` both become `openlibrary:OL66554W`. */
function readWorkId(value: unknown): string | undefined {
  const key = readString(value);
  if (key === undefined) return undefined;
  const segment = key
    .split('/')
    .filter((part) => part.length > 0)
    .pop();
  if (segment === undefined) return undefined;
  return `${OPENLIBRARY_ID_PREFIX}${segment}`;
}

/** Old records carry a plain string, new ones `{ type, value }`. */
function readDescription(value: unknown): string | undefined {
  if (typeof value === 'string') return readString(value);
  return isPlainObject(value) ? readString(value.value) : undefined;
}

function readReleaseInfo(year: unknown, date: unknown): string | undefined {
  if (typeof year === 'number' && Number.isInteger(year)) return String(year);
  return readString(date);
}

function readCover(coverId: unknown, covers: unknown): string | undefined {
  const candidate =
    typeof coverId === 'number' && Number.isInteger(coverId) && coverId > 0
      ? coverId
      : Array.isArray(covers) && typeof covers[0] === 'number' && covers[0] > 0
        ? covers[0]
        : undefined;
  return candidate === undefined ? undefined : `${COVER_URL_PREFIX}${candidate}${COVER_URL_SUFFIX}`;
}

function readIsbn13(value: unknown): string | undefined {
  if (!Array.isArray(value)) return undefined;
  for (const entry of value) {
    if (typeof entry === 'string' && /^\d{13}$/.test(entry)) return entry;
  }
  return undefined;
}

export function shapeOpenlibraryMeta(body: unknown): MetaPreview | null {
  if (!isPlainObject(body)) return null;
  const id = readWorkId(body.key);
  const name = readString(body.title);
  if (id === undefined || name === undefined) return null;

  const meta: MetaPreview = {
    id,
    type: TYPE_BOOK,
    name,
    authors: readStringArray(body.author_name),
    languages: readStringArray(body.language),
    subjects: readStringArray(body.subjects ?? body.subject),
  };
  const poster = readCover(body.cover_i, body.covers);
  if (poster !== undefined) meta.poster = poster;
  const description = readDescription(body.description);
  if (description !== undefined) meta.description = description;
  const releaseInfo = readReleaseInfo(body.first_publish_year, body.first_publish_date);
  if (releaseInfo !== undefined) meta.releaseInfo = releaseInfo;
  const publisher = readStringArray(body.publishers)[0];
  if (publisher !== undefined) meta.publisher = publisher;
  const isbn13 = readIsbn13(body.isbn);
  if (isbn13 !== undefined) meta.isbn13 = isbn13;
  return meta;
}

export function shapeOpenlibraryCatalog(body: unknown): CatalogPayload {
  if (!isPlainObject(body) || !Array.isArray(body.docs)) {
    return { metas: [], skipped: 1 };
  }

  const metas: MetaPreview[] = [];
  let skipped = 0;
  for (const entry of body.docs) {
    const meta = shapeOpenlibraryMeta(entry);
    if (meta === null) {
      skipped += 1;
      continue;
    }
    metas.push(meta);
  }
  return { metas, skipped };
}
