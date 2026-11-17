# Desktop CSP origins — derivation and why each entry survives

The webview CSP lives in `desktop/src-tauri/tauri.conf.json` under
`app.security.csp`. This document records how every origin in `connect-src`
and `img-src` was derived from code, so a future change cannot restore a broad
allowlist "just in case".

Why it matters: an XSS in the renderer inherits `connect-src`. Every origin on
that list is an exfiltration target. Rust commands have no CSP — the network
boundary that matters is the one the renderer itself can reach.

`style-src 'unsafe-inline'` stays. Tailwind 4 injects its runtime styles
inline, and the directive is not the exfiltration vector here — `connect-src`
is.

## Method

1. Enumerate every URL literal the desktop tree touches:

   ```
   rg --no-ignore --hidden "https://[a-zA-Z0-9.-]+" desktop/src desktop/src-tauri/src \
     --glob '!**/test/**' -o | sort | uniq -c | sort -rn
   ```

2. Classify each origin by the process that calls it. Only a URL requested by
   the **webview** needs a CSP entry. Rust-only origins are removed: `reqwest`
   in `src-tauri` is not governed by the webview CSP, so listing them grants the
   renderer reach it never uses.

3. Distinguish **fetch** from **navigation**. A `<a href target="_blank">` opens
   the system browser via the opener plugin; it never resolves under
   `connect-src`. A `fetch()` / `XMLHttpRequest` / image `src` does.

4. `desktop/index.html` was checked: it loads only `./favicon.svg` and
   `/src/main.ts`, both `'self'`. No external `<link>` or `<script>`.

## connect-src

| Origin | Who calls it | Verdict |
| --- | --- | --- |
| `'self'` | The app bundle itself | **kept** — required |
| `asset:` / `http://asset.localhost` / `https://asset.localhost` | Tauri asset protocol for in-app EPUB files and fonts | **kept** — platform plumbing |
| `http://ipc.localhost` | Tauri IPC. Without it every `invoke` degrades to postMessage in production | **kept** — platform plumbing |
| `https://accounts.google.com` | `DriveConnectService.ts:42` — `openUrl` of the OAuth consent page. **Reachability clarification:** this is a browser launch, so the page itself does not need `connect-src`; the entry is retained because `openUrl` is allowlisted in `capabilities/default.json` and the redirect target is inferred from it. Kept for behavioral parity with the Drive flow. | **kept** |
| `https://oauth2.googleapis.com` | `DriveConnectService.ts:43,44` — `fetch` TOKEN_ENDPOINT (exchange) and REVOKE_ENDPOINT (disconnect) | **kept** — real webview `fetch` |
| `https://www.googleapis.com` | `GDriveProvider.ts:57,58` — `fetch` Drive API and upload base; `GoogleBooksDataSource.ts:28` — `fetchWithRetry` on the Books API | **kept** — real webview `fetch` |
| `https://*.supabase.co` | `$lib/services/supabase.ts` builds the client from `VITE_SUPABASE_URL`; every `Supabase*Sync` calls it via supabase-js | **kept** — real webview client |
| `https://gutendex.com` | `GutendexDataSource.ts:17` — default `fetchFn = fetch` | **kept** — real webview `fetch` |
| `https://openlibrary.org` | `OpenLibraryDataSource.ts:18` — default `fetchFn = fetch` | **kept** — real webview `fetch` |
| `https://covers.openlibrary.org` | `mappers.ts:52` — cover image `src` | **kept** — image load |
| `https://standardebooks.org` | `StandardEbooksDataSource.ts:60` — `catalogFeedFetch`, which invokes the Rust `fetchCatalogFeed` command | **removed** — Rust-only |
| `https://*.archive.org` | `accessResolver.ts:42` — builds an `<a href target="_blank">` link only; the `<a>` opens externally. No webview fetch, no image load | **removed** — navigation, not `connect-src` |
| `https://github.com` | `SettingsAboutTab.svelte:47,48` — `openUrl` of the repo/issues links. Release feed is read by the Rust updater | **removed** — navigation only |
| `https://www.wiktionary.org`, `https://*.wiktionary.org` | `commands/define.rs:173` — Rust `reqwest` | **removed** — Rust-only |
| `https://api.mymemory.translated.net` | `commands/translate.rs:33` — Rust `reqwest` | **removed** — Rust-only |
| `https://127.0.0.1` | `commands/translate.rs:561`, `commands/define.rs:857` — Rust test/refused-endpoint constants | **removed** — Rust-only |

## img-src

| Origin | Who calls it | Verdict |
| --- | --- | --- |
| `'self'`, `asset:`, `http://asset.localhost`, `https://asset.localhost`, `data:`, `blob:` | In-app covers via `SafeCover.svelte`, fallback initials, object URLs | **kept** |
| `https://covers.openlibrary.org` | `mappers.ts:52` — Open Library cover | **kept** |
| `https://www.gutenberg.org` | `mappers.ts:61` — Gutenberg cover | **kept** |
| `https://books.google.com` | `mappers.ts:296` — Google Books thumbnail | **kept** |
| `https://standardebooks.org` | `mappers.ts:415` — the Atom feed's `<media:thumbnail>` | **kept** — rendered as an `<img>`, so it needs `img-src` even though the feed bytes come from Rust |
| `https://lh3.googleusercontent.com` | **No code reference anywhere** in `desktop/src` or `desktop/src-tauri/src` | **removed** — dead entry |
| `https://xshgfrwqiddaaecqmicy.supabase.co` | The project-specific Supabase origin. Rendered as `https://*.supabase.co` in no image path; `SafeCover` only loads `https:` remote covers from mapper outputs | **removed** — no image caller names it |

Note on the two Supabase spellings: the static key appeared in `img-src` while
`connect-src` carried the wildcard. No image code produces a `supabase.co` URL,
so the image entry went away. `connect-src` keeps the wildcard because the
client URL is environment-driven (`VITE_SUPABASE_URL`), not a literal.

## Result

`connect-src` went from 19 entries to 10; `img-src` from 13 to 10. Six origins
were removed because Rust alone calls them, one because its only caller is an
`<a>` navigation, one because it was dead.

## Static verification

Every kept origin has a named live call site in the table above. Every removed
origin has a call site in `src-tauri` or in a `target="_blank"` anchor, never
in a webview `fetch`/image.

**Not verified here:** a full session smoke test with the narrowed policy
(`npm run tauri dev`, then walk discover → detail → download → cover render →
Drive connect → sync → translation → definition) watching the console for a
`Content Security Policy` violation. This host cannot run the app, so the
runtime confirmation is required before release.

## Known pre-existing gap (not introduced here)

Supabase Realtime opens a WebSocket (`wss://<project>.supabase.co`) — see
`.channel(...)` in `SupabaseProgressSync.ts`, `SupabaseDictionarySync.ts`,
`SupabaseBookCatalogSync.ts` and `useDeviceRealtime.svelte.ts`. No `wss:` source
is declared in `connect-src`, so those channels are already blocked by the
policy. This is unchanged by this narrowing and is recorded here so the next
person does not attribute it to the narrowed list. Fixing it is a separate,
deliberate decision: adding `wss://*.supabase.co` widens the exfiltration
surface and should be justified by a realtime requirement.
