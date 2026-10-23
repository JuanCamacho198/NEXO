# NEXO addon protocol v3

Status: contract for `feat/addon-protocol-v3`.
Scope: what an addon author must serve for NEXO (desktop and Android) to install and
use the addon. It describes the implementation in `packages/addon-protocol`, which is
the executable specification; where prose and code disagree, the code is right.

NEXO uses a Stremio-shaped subset of the addon protocol, scoped to books. If you
already ship a Stremio addon, most of the manifest will look familiar. Section 1 lists
where NEXO is stricter, narrower, or different, because those differences decide the
design of the addon, not the details of the JSON.

## 1. How NEXO differs from Stremio

1. **A stream starts a lifecycle.** Stremio plays a URL and forgets it. NEXO downloads
   the file, parses it, creates a book in the library, and tracks reading progress over
   years. A stream must therefore point at a durable file or a page the user can open,
   not at a transient playback URL.
2. **There is no shared book identity.** Stremio has `tt` ids that every addon agrees on
   and Cinemeta resolves centrally. Books have no equivalent, so NEXO freezes a set of id
   prefixes (section 9) and stores the addon id that produced an item.
3. **There is no central metadata service.** A catalog item the addon cannot describe
   through `meta` renders an empty detail view. The catalog and meta resources are
   therefore one contract, not two (section 10).
4. **Most book addons cannot be servers.** NEXO supports two tiers: a routed addon that
   hosts endpoints, and a templated addon that only declares URL templates. The second
   tier exists because the upstream APIs NEXO integrates with will never speak this
   protocol (section 5).

## 2. Manifest transport

The install URL points at the manifest and must use `https`. A non-HTTPS URL, including
`http://localhost`, is rejected before any network request is made.

| Rule | Value |
|------|-------|
| Scheme | `https` only, no loopback exemption |
| Size cap | 65536 bytes (64 KiB) uncompressed, checked before parsing |
| Content type | `application/json`, or any type ending in `+json`; parameters ignored |
| Encoding | valid UTF-8; invalid bytes are a manifest error, not a replacement glyph |
| Cookies, credentials, ambient auth | never sent |

Validation order is fixed and visible in the error code you get: size, content type,
JSON parse, then shape.

## 3. Manifest v3 fields

```jsonc
{
  "id": "org.example.books",
  "version": "1.0.0",
  "name": "Example Books",
  "description": "Public-domain EPUBs.",        // optional
  "types": ["book"],
  "catalogs": [
    {
      "type": "book",
      "id": "popular",
      "name": "Popular",
      "extra": [
        { "name": "search", "isRequired": false },
        { "name": "skip", "isRequired": false }
      ]
    }
  ],
  "resources": [
    "catalog",
    { "name": "meta", "types": ["book"] },
    { "name": "stream", "types": ["book"], "idPrefixes": ["gutenberg:"] }
  ],
  "idPrefixes": ["gutenberg:"],                 // optional
  "logo": "https://example.org/logo.png",       // optional, HTTPS
  "background": "https://example.org/bg.jpg",   // optional, HTTPS
  "behaviorHints": { "adult": false },          // optional
  "nexo": {                                     // optional, NEXO-only
    "homepage": "https://example.org",
    "license": "CC0-1.0",
    "templates": {
      "catalog": "https://api.example.org/search?q={search}",
      "meta": "https://api.example.org/books/{id}",
      "stream": "https://api.example.org/books/{id}/files"
    },
    "shape": "raw"
  }
}
```

Required fields: `id`, `version`, `name`, `types`, `resources`. `catalogs` may be
omitted or empty, which is how a stream-only addon is declared.

| Field | Required | Shape and limit |
|-------|----------|-----------------|
| `id` | yes | non-empty string, at most 2048 chars. Stable for the life of the addon |
| `version` | yes | non-empty string, at most 2048 chars. Not parsed as semver |
| `name` | yes | non-empty string, at most 2048 chars |
| `description` | no | non-empty string, at most 2048 chars |
| `types` | yes | non-empty array, at most 16 entries, each at most 64 chars |
| `catalogs` | no | at most 16 entries |
| `catalogs[].type` | yes | string, at most 512 chars. Only `book` is kept |
| `catalogs[].id` | yes | string, at most 512 chars. This is the route id, not an item id |
| `catalogs[].name` | yes | string, at most 512 chars |
| `catalogs[].extra` | no | at most 8 entries, each `name` at most 64 chars plus optional boolean `isRequired` |
| `resources` | yes | non-empty array, at most 16 entries |
| `resources[]` | yes | a string, or an object with a `name` (at most 64 chars) |
| `resources[].types` | no | non-empty array, at most 16 entries, each at most 64 chars |
| `resources[].idPrefixes` | no | array, at most 16 entries, each at most 64 chars |
| `idPrefixes` | no | array, at most 16 entries, each at most 64 chars |
| `logo`, `background` | no | HTTPS URL, at most 2048 chars |
| `behaviorHints.adult` | no | boolean; any other key under `behaviorHints` is ignored |
| `nexo.homepage` | no | HTTPS URL, at most 2048 chars |
| `nexo.license` | no | non-empty string, at most 2048 chars |
| `nexo.templates` | no | at most 16 entries; key at most 64 chars; value an HTTPS URL at most 2048 chars |
| `nexo.shape` | no | one of `raw`, `gutendex`, `openlibrary`, `legacy-v2` |
| `nexo` | no | the serialized object must be at most 8192 chars |

`resources` must be non-empty even when every request is served by a template. A
template makes a resource routable, but it does not replace the declaration.

Shape limits are hard: exceeding one is `ADDON_FETCH_INVALID_MANIFEST`, never a
truncation. Two limits have their own code: the size cap (`ADDON_FETCH_TOO_LARGE`) and a
reserved type (`ADDON_FETCH_UNSUPPORTED_TYPE`). Response payloads are the exception:
over-long strings are truncated and excess items are counted as skipped (section 7).

## 4. What NEXO ignores

Ignored means accepted without error and without effect:

- unknown top-level fields;
- keys under `behaviorHints` other than `adult` at manifest level;
- keys under `nexo` other than `homepage`, `license`, `templates`, `shape`. The object
  is bounded but not preserved verbatim: unknown keys are dropped;
- catalog entries whose `type` is not `book`. The addon installs and that catalog
  contributes nothing;
- catalog `extra` entries whose `name` is not `search`, `skip`, `genre` or `lang`;
- resources with any name other than `catalog`, `meta` or `stream`. They are stored and
  never routed;
- unknown fields in response envelopes, including per-stream extras such as
  `bingeGroup`;
- the `subtitles` resource. It is not in this protocol revision.

`behaviorHints.bittorrent` is not ignored: it rejects the stream (section 11).

## 5. Two tiers

| Tier | Declares | Request URL | Response |
|------|----------|-------------|----------|
| **A, routed** | `resources` only | built by the client from the install URL: `/{resource}/{type}/{id}[/{extra}].json` | Stremio envelope |
| **B, templated** | `nexo.templates[resource]` | your template with `{...}` placeholders filled | envelope, or the named `nexo.shape` for `catalog` and `meta` |

Dispatch is per resource. A template for a resource wins over the routed form for that
same resource; every other resource still uses its route. A templated addon is
selectable for a request even when `resources` does not mention that resource, because
the template is all the client needs to build a URL.

Templates substitute a closed set of placeholders and do nothing else:

| Placeholder | Value |
|-------------|-------|
| `{search}`, `{query}` | the search string |
| `{skip}`, `{page}` | the skip offset |
| `{genre}` | the genre filter |
| `{lang}` | the language filter |
| `{id}`, `{bookId}` | the item id, prefixed (`gutenberg:1342`) |

Every substituted value is percent-encoded, with `%3A` restored to `:` so a prefixed id
stays readable. A template that is not given an argument substitutes an empty string; the
client never adds an extra the catalog did not declare. An unknown placeholder such as
`{publisher}` is a manifest error, reported when the request is built rather than at
install.

The templated tier performs no arithmetic and no prefix stripping. It cannot turn
`skip=100` into `page=2`, and it cannot turn `gutenberg:1342` into `1342`. If your
upstream needs either, either host a small adapter (Tier A, or a template pointing at
your own path) or do not declare that resource as a template.

The tradeoff is deliberate: `nexo.shape` names a client-bundled adapter, so an addon
that depends on one is portable only to clients that ship the same adapter. Shapes are
not portable across clients, and NEXO does not accept shape plugin code from a manifest.

## 6. Routes

The routed base is the install URL with a trailing `/manifest.json` and any trailing
slashes removed. `https://books.example.org/addons/example/manifest.json` serves routes
under `https://books.example.org/addons/example`.

| Resource | Route | Response |
|----------|-------|----------|
| `catalog` | `/catalog/book/{catalogId}.json` | `{ "metas": [...] }` |
| `catalog` | `/catalog/book/{catalogId}/search=pride%20and%20prejudice&skip=0.json` | `{ "metas": [...] }` |
| `meta` | `/meta/book/{id}.json` | `{ "meta": { ... } }` |
| `stream` | `/stream/book/{id}.json` | `{ "streams": [...] }` |

Path segments are percent-encoded, with `:` kept readable. A `type` or `id` that
contains `/` is a request error rather than a forgeable extra segment. Extra arguments
are serialized in insertion order as `key=value` joined by `&`, with spaces as `%20`
(`+` is never used) and empty values omitted.

## 7. Envelopes

Every payload is untrusted. Parsing is total: an unreadable entry is skipped and
counted, it does not fail the request. A body that is not an envelope of the expected
kind yields an empty result.

Caps: at most 200 `metas`, at most 100 `streams`, any string field at most 2048 chars
(truncated, not rejected), and the serialized body at most 512 KiB. A body above that
cap is discarded as a failed request.

### Catalog

```json
{
  "metas": [
    {
      "id": "gutenberg:1342",
      "type": "book",
      "name": "Pride and Prejudice",
      "poster": "https://media.example.org/covers/1342.jpg",
      "description": "Elizabeth Bennet, Mr Darcy, and the society that judges them.",
      "releaseInfo": "1813",
      "authors": ["Jane Austen"],
      "languages": ["en"],
      "subjects": ["Courtship", "England", "Fiction"],
      "isbn13": "9780141439518"
    }
  ]
}
```

`id` and `name` are required; an entry without either is skipped. `type` defaults to
`book`. `authors`, `languages` and `subjects` accept bare strings or `{ "name": "..." }`
objects, and unreadable entries inside them are dropped. `isbn13` is read from the field,
or derived when the id is `isbn:` followed by 13 digits.

### Meta

```json
{
  "meta": {
    "id": "gutenberg:1342",
    "type": "book",
    "name": "Pride and Prejudice",
    "poster": "https://media.example.org/covers/1342.jpg",
    "description": "Elizabeth Bennet, Mr Darcy, and the society that judges them.",
    "releaseInfo": "1813",
    "publisher": "Penguin Classics",
    "authors": ["Jane Austen"],
    "languages": ["en"],
    "subjects": ["Courtship", "England", "Fiction"],
    "isbn13": "9780141439518"
  }
}
```

The object is read with the same rules as a catalog entry, from under `meta`. `publisher` is
read from either envelope, and on a `meta` answer it is also what a metadata addon
contributes to a book the reader already owns: it fills the library's `publisher` column
only when that column is empty.

### Streams

```json
{
  "streams": [
    {
      "name": "Example Books",
      "title": "EPUB, public domain",
      "url": "https://media.example.org/files/pride-and-prejudice.epub",
      "behaviorHints": { "accessType": "public-domain", "format": "epub" }
    },
    {
      "name": "Open Library",
      "title": "Borrow, EPUB",
      "externalUrl": "https://openlibrary.org/borrow/ia/prideandprejudice00aust",
      "behaviorHints": { "accessType": "borrow" }
    }
  ]
}
```

`url` is a file NEXO downloads and imports. `externalUrl` is a page NEXO opens in the
browser. When both are present, `url` wins. `behaviorHints.accessType` is one of
`public-domain`, `borrow`, `purchase`; anything else, including absence, is `unknown`.
`behaviorHints.format` is `epub` or `pdf`; when absent, a path ending in `.pdf` is `pdf`
and everything else is `epub`.

The final option list is ordered by product rules, not by install order:
`public-domain` → `borrow` → `purchase` → `unknown`, then `epub` → `pdf`, then addon
install time, then addon id.

NEXO asks for per-addon consent before it sends any `stream` request. `catalog` and
`meta` requests are read-only descriptions of items the addon itself listed, and need no
consent: asking about a book the addon just offered discloses nothing the addon did not
already serve.

A `meta` request about a book already in the reader's library is a different case. The
library is the reader's own shelf, so a request naming one of its items tells the addon
which books that reader owns, one id at a time. That ask is therefore a separate scope,
`library_meta`, opted in per addon: it defaults to off, and consent for `stream` — or
adding the addon to a collection — never implies it. An addon is only asked under that
scope when it also declares `meta` with `idPrefixes` that match the book's identifier; an
addon declaring `meta` with no prefixes is never asked about a library book at all.

## 8. Extra arguments

The four names are `search`, `skip`, `genre` and `lang`. `catalogs[].extra` is the
authoritative allow-list per catalog: a name outside the four is dropped before routing,
so the client never sends it. A name inside the four but absent from the catalog's
`extra` is not sent either.

## 9. Id prefixes

Emitting item ids with one of these prefixes is part of the contract:

```text
gutenberg:  isbn:  openlibrary:  googlebooks:  archive:  standardebooks:  librivox:
```

They are frozen. The prefix survives in `books.source_item_id` on Android, in desktop
SQLite, and in Supabase, so renaming one is a three-database migration, not a rename.
Item ids are namespaced by whatever you emit; a prefix NEXO does not know is accepted,
but provenance and deduplication will not recognize it.

`idPrefixes` filtering uses `startsWith` and applies only to `meta` and `stream`, because
only those two address a single item. A catalog id is a catalog identifier, never an item
id. The list on a resource overrides the manifest-level list; a request without an id is
not filtered.

## 10. Types and catalogs

`book` is the only implemented type.

`audiobook` and `comic` are reserved. Declaring either in `types` or in
`resources[].types` rejects the install with `ADDON_FETCH_UNSUPPORTED_TYPE`. They are
rejected rather than ignored so that adding support later is additive. A catalog entry
with `type: "audiobook"` is a different case: catalog types are matched, not validated,
so that entry is dropped and the rest of the addon installs.

A catalog entry the addon cannot describe through `meta` is not installable: `catalogs`
and `meta` are one unit. When `catalogs` is non-empty, `resources` MUST declare `meta`,
and a manifest that lists books without it is rejected with
`ADDON_FETCH_INVALID_MANIFEST`; the message names the missing resource. An empty
`catalogs` list still installs without `meta`, so a stream-only addon declares nothing
extra. Item ids are namespaced to the addon that produced them, and the detail view
resolves through the addon's `meta` resource.

## 11. Streams

Exactly one function accepts or rejects a stream. It rejects, without error, any entry
that carries:

- an `infoHash` key, even when its value is `null`. Presence is what rejects;
- a `behaviorHints` object containing a `bittorrent` key;
- a `sources` array containing an entry whose `type` is `torrent` (any casing);
- neither `url` nor `externalUrl`;
- a link whose scheme is not `http` or `https`. `magnet:` and `data:` are torrent and
  script vectors, so they never reach the option list.

A rejected entry is skipped and counted. A torrent-only addon installs, answers requests,
contributes no options, and never produces an error the user sees. There is no
configuration flag that turns torrents back on.

`nexo.shape` does not apply to streams: they are always read from a `{ "streams": [...] }`
envelope, whatever the shape. A shape adapter that describes a third-party catalog
therefore does not make that third party able to serve files.

## 12. Response adapters (`nexo.shape`)

`nexo.shape` is a closed set and defaults to `raw`, which means the Stremio envelope.

| Shape | `catalog` | `meta` | `stream` |
|-------|-----------|--------|----------|
| `raw` | envelope | envelope | envelope |
| `gutendex` | Gutendex `/books` (`results[]`) | Gutendex `/books/{id}` | envelope |
| `openlibrary` | Open Library search (`docs[]`) | Open Library work | envelope |
| `legacy-v2` | v2 `results[]` book list | v2 book object | envelope |

That table is the whole set. A name outside it is not a shape: the manifest fails with
`ADDON_FETCH_INVALID_MANIFEST` and nothing is installed. Do not pick a shape you are not
matching either: a mismatched shape reads as empty, not as an error.

`standard-ebooks` and `librivox` are planned, not supported. No build ships a reader for
either, so neither is in the set, and a manifest that names one is rejected at install with
`ADDON_FETCH_INVALID_MANIFEST` — the same answer an invented name gets. Until a revision
adds those readers, serve those upstreams with `nexo.templates` (section 5) or answer the
protocol routes yourself with a `raw` envelope.

The `legacy-v2` adapter reads the format the installed v2 addons already serve:
`searchUrl` answers `{ "results": [book…], "totalCount": n }` and `detailsUrl` answers a
single book. A book is flat on both endpoints — `id`, `title`, `authors[]`, `coverUrl`,
`languages[]`, `subjects[]`, `downloadUrl` — with no metadata envelope anywhere. `id`
becomes the item id verbatim, because the addon resolves its own ids through `{bookId}`;
`title` becomes `name`; `authors`, `languages` and `subjects` become their lists; a
`coverUrl` becomes `poster` only when it is `https`. `downloadUrl` is not carried, since
files come from `stream` and a `stream` answer is always the Stremio envelope
(section 11); `totalCount` is ignored. There is no publisher in that format either, so a
`legacy-v2` book leaves the library's `publisher` column untouched rather than guessing one.

The Gutendex adapter prefixes `id` with `gutenberg:`, maps `title` to `name`, maps
`authors`, `languages` and `subjects` to their lists, and takes the `image/jpeg` entry of
`formats` as `poster`. The Open Library adapter turns a work key into
`openlibrary:{key}`, builds `poster` from the cover id, and takes the first entry of
`publishers` as `publisher`. Neither adapter reads `formats` for downloads.

## 13. Worked example: a templated addon

This addon serves a catalog and metadata from Gutendex, and stream options from a small
path the author controls, because Gutendex has no file endpoint.

```json
{
  "id": "org.example.gutendex",
  "version": "1.0.0",
  "name": "Gutendex",
  "description": "Project Gutenberg metadata from gutendex.com.",
  "types": ["book"],
  "catalogs": [
    {
      "type": "book",
      "id": "search",
      "name": "Project Gutenberg",
      "extra": [{ "name": "search", "isRequired": false }]
    }
  ],
  "resources": [
    { "name": "catalog", "types": ["book"] },
    { "name": "meta", "types": ["book"] },
    { "name": "stream", "types": ["book"] }
  ],
  "idPrefixes": ["gutenberg:"],
  "nexo": {
    "homepage": "https://gutendex.com",
    "license": "CC0-1.0",
    "shape": "gutendex",
    "templates": {
      "catalog": "https://gutendex.com/books?search={search}",
      "meta": "https://books.example.org/gutendex/book/{id}",
      "stream": "https://books.example.org/gutendex/book/{id}/files"
    }
  }
}
```

What the client sends:

| Request | URL |
|---------|-----|
| catalog search for `pride` | `https://gutendex.com/books?search=pride` |
| meta for the item `gutenberg:1342` | `https://books.example.org/gutendex/book/gutenberg:1342` |
| streams for the same item | `https://books.example.org/gutendex/book/gutenberg:1342/files` |

Three decisions in that manifest are worth copying:

- **No `{skip}`.** The catalog declares `search` only. Declaring `skip` without a
  placeholder in the template would advertise paging that never happens, because
  Gutendex pages are 1-based and templates cannot do the arithmetic.
- **`meta` points at the author's path, not at Gutendex.** Gutendex serves
  `/books/1342`; the template receives `gutenberg:1342`, and the tier does not strip the
  prefix. The author's path strips it and returns the raw Gutendex book object.
- **`stream` returns the Stremio envelope.** The shape adapts `catalog` and `meta` only,
  so `/files` answers `{ "streams": [ ... ] }` with `url` pointing at the EPUB.

The catalog response from Gutendex is shaped, not passed through: `results[]` becomes
`metas[]`, numeric ids become `gutenberg:{n}`, and the JPEG cover becomes `poster`.
Anything the adapter cannot read is counted as skipped.

## 14. Worked example: a routed addon

The author hosts `https://books.example.org/addons/example/manifest.json`.

```json
{
  "id": "org.example.books",
  "version": "1.0.0",
  "name": "Example Books",
  "description": "Public-domain EPUBs and library loans.",
  "types": ["book"],
  "catalogs": [
    {
      "type": "book",
      "id": "popular",
      "name": "Popular",
      "extra": [
        { "name": "search", "isRequired": false },
        { "name": "skip", "isRequired": false }
      ]
    }
  ],
  "resources": [
    { "name": "catalog", "types": ["book"] },
    { "name": "meta", "types": ["book"] },
    { "name": "stream", "types": ["book"], "idPrefixes": ["gutenberg:", "isbn:"] }
  ],
  "idPrefixes": ["gutenberg:", "isbn:"],
  "logo": "https://books.example.org/logo.png",
  "nexo": {
    "homepage": "https://books.example.org",
    "license": "CC-BY-4.0",
    "shape": "raw"
  }
}
```

No templates, so every resource is routed. The base is
`https://books.example.org/addons/example`.

`GET /catalog/book/popular.json` and
`GET /catalog/book/popular/search=pride%20and%20prejudice&skip=0.json` both answer:

```json
{
  "metas": [
    {
      "id": "gutenberg:1342",
      "type": "book",
      "name": "Pride and Prejudice",
      "poster": "https://media.example.org/covers/1342.jpg",
      "releaseInfo": "1813",
      "authors": ["Jane Austen"],
      "languages": ["en"],
      "subjects": ["Courtship", "England", "Fiction"],
      "isbn13": "9780141439518"
    }
  ]
}
```

`GET /meta/book/gutenberg:1342.json` answers with the same description under `meta`:

```json
{
  "meta": {
    "id": "gutenberg:1342",
    "type": "book",
    "name": "Pride and Prejudice",
    "poster": "https://media.example.org/covers/1342.jpg",
    "releaseInfo": "1813",
    "authors": ["Jane Austen"],
    "languages": ["en"],
    "subjects": ["Courtship", "England", "Fiction"],
    "isbn13": "9780141439518"
  }
}
```

`GET /stream/book/gutenberg:1342.json` answers:

```json
{
  "streams": [
    {
      "name": "Example Books",
      "title": "EPUB, public domain",
      "url": "https://media.example.org/files/pride-and-prejudice.epub",
      "behaviorHints": { "accessType": "public-domain", "format": "epub" }
    },
    {
      "name": "Open Library",
      "title": "Borrow, EPUB",
      "externalUrl": "https://openlibrary.org/borrow/ia/prideandprejudice00aust",
      "behaviorHints": { "accessType": "borrow" }
    }
  ]
}
```

The first option sorts before the second: `public-domain` outranks `borrow`. If the
author later declares `"nexo": { "templates": { "stream": "https://…/{id}" } }`, only
the stream resource moves to the templated tier; catalog and meta keep their routes.

## 15. Version 2 manifests

A manifest without `types` and `resources` is read as a v2 manifest and adapted, so the
v2 field names keep working while v2 addons convert:

| v2 | v3 |
|----|----|
| `catalogs[].type: "book-catalog"` | `"book"` |
| `catalogs[].extra` | `search` and `skip` when `searchUrl` is present |
| `searchUrl` | `nexo.templates.catalog`, `{query}` → `{search}`, `{page}` → `{skip}` |
| `detailsUrl` | `nexo.templates.meta`, `{bookId}` → `{id}` |
| `resolveUrl` | `nexo.templates.stream`, `{bookId}` → `{id}` |
| `capabilities: ["resolve"]` | the `stream` resource |
| `id`, `name`, `version` | unchanged |

A manifest that lacks `types`, or whose `resources` is not an array of strings or objects
carrying a `name`, is read as v2 and reported by the v2 validator. The dispatch is a
structural probe, not a version field, so a malformed v3 manifest can surface as a v2
shape error.

`nexo.shape` becomes `legacy-v2`. New addons should publish the v3 form: templates and
shapes are the contract, and the v2 adapter exists for continuity, not as a target.

## 16. Testing your addon

```bash
bun packages/addon-protocol/src/cli.ts -- https://books.example.org/addons/example/manifest.json
```

The CLI applies the same transport and shape rules as the install path: HTTPS before any
I/O, 64 KiB bounded read, content type, then dispatch on v2 or v3. Exit code `0` means
valid; any failure exits `1` and prints the stable code on stderr:

| Code | Meaning |
|------|---------|
| `ADDON_FETCH_HTTPS_REQUIRED` | the URL is not HTTPS; no request was made |
| `ADDON_FETCH_TOO_LARGE` | the body exceeded 64 KiB; reading stopped |
| `ADDON_FETCH_BAD_CONTENT_TYPE` | not JSON, and not `+json` |
| `ADDON_FETCH_INVALID_MANIFEST` | not UTF-8 JSON, or a field breaks the shape |
| `ADDON_FETCH_UNSUPPORTED_TYPE` | a reserved type was declared |
| `ADDON_FETCH_NETWORK` | transport failure, non-2xx status, or timeout |

A valid manifest prints the summary it parsed, so a mistake in `nexo` is visible
immediately:

```text
Valid manifest: https://books.example.org/addons/example/manifest.json
id: org.example.books
name: Example Books
version: 1.0.0
types: book
catalogs: 1
resources: catalog, meta, stream
shape: raw
```

The CLI checks the manifest. It does not request your routes, so test `catalog`, `meta`
and `stream` by hand, including one request with extras and one for an id whose prefix
you declare.

## 17. Not in this revision

`subtitles`, `manifest.config` configuration pages, IPFS transports and instant listing
are out of scope. Declaring a `subtitles` resource is accepted and ignored, which is why
an addon that relies on it will appear to install correctly and do nothing.
