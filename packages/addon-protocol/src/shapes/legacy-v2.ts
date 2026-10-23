/**
 * `legacy-v2` shape adapter: the wire format the installed v2 addons serve.
 * Pure, no network.
 *
 * A v2 addon answers `searchUrl` with `{ "results": [book...], "totalCount": n }`
 * and `detailsUrl` with a single book object. Every book is flat — `id`,
 * `title`, `authors[]`, `coverUrl`, `languages[]`, `subjects[]`, `downloadUrl`
 * — and there is no metadata envelope on either endpoint. Streams are the one
 * exception: `resolveUrl` answers the Stremio `{ "streams": [...] }` envelope,
 * which is why this module shapes `catalog` and `meta` only.
 *
 * Ids pass through verbatim. The addon resolves its own ids through its own
 * template (`{bookId}`), so prefixing one here would send the addon an id it
 * cannot find.
 *
 * There is no publisher in the v2 book object, so `publisher` stays undefined:
 * inventing a source (an author, a collection name) would be a guess written
 * into the reader's library.
 */

import { TYPE_BOOK } from '../constants';
import type { CatalogPayload, MetaPreview } from '../payload';

const MAX_STRING_CHARS = 2048;

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

/** The v2 adapters accept a cover only over https; anything else has no poster. */
function readHttpsUrl(value: unknown): string | undefined {
  const url = readString(value);
  return url !== undefined && url.startsWith('https://') ? url : undefined;
}

/**
 * One v2 book. `id` and `title` are the v2 adapter's two mandatory fields;
 * without them the entry is not a book. `downloadUrl` is deliberately not read:
 * a file URL is a `stream`, and the protocol path takes files from there.
 */
export function shapeLegacyV2Meta(body: unknown): MetaPreview | null {
  if (!isPlainObject(body)) return null;
  const id = readString(body.id);
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
  const poster = readHttpsUrl(body.coverUrl);
  if (poster !== undefined) meta.poster = poster;
  return meta;
}

export function shapeLegacyV2Catalog(body: unknown): CatalogPayload {
  if (!isPlainObject(body) || !Array.isArray(body.results)) {
    return { metas: [], skipped: 1 };
  }

  const metas: MetaPreview[] = [];
  let skipped = 0;
  for (const entry of body.results) {
    const meta = shapeLegacyV2Meta(entry);
    if (meta === null) {
      skipped += 1;
      continue;
    }
    metas.push(meta);
  }
  return { metas, skipped };
}
