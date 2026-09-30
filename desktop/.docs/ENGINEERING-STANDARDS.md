# Engineering Standards — Nexo Desktop

> 4 commandments + architecture and quality gates for Svelte 5 + Tauri. RDD review is DISABLED — manual review only.

## 1. Svelte 5 — 4 Commandments

### 1.1 $derived is pure, $effect is side-effect
`$derived` must be pure computation. Mutating state inside `$derived` triggers `state_unsafe_mutation`.

```ts
// BAD — mutates inside derived (broke 9 tests in P2-C1)
let _zoomSync = $derived.by(() => { zoom.syncFromProps(readerSettings); return readerSettings; });

// GOOD
$effect(() => { if (readerSettings) zoom.syncFromProps(readerSettings); });

// GOOD — pure derived
const readerCss = $derived.by(() => buildReaderOverrideCss(readerSettings));
```

### 1.2 Logic in .svelte.ts, UI in .svelte
If `<script>` exceeds ~200 lines of DOM/parsing, extract to `useThing.svelte.ts`.

- `pdfState.svelte.ts` falsely claimed `$state` impossible in `.svelte.ts` — it IS supported. Use `$state` in `.svelte.ts` for composables (`usePdfDocument`, `useEpubRender`).
- `EpubNativeViewer` 2013L → 6 composables (`useEpubSpine`, `useEpubNavigation`, `useEpubRender`, `useEpubBridge`, `useEpubHighlights`, `useEpubZoomTheme`).

### 1.3 Snippet before copy-paste
If markup repeats twice, extract `{#snippet}`.

```svelte
{#snippet shelfBookCard(book)}
  <BookCard {book} /><ShelfActionMenu {book} />
{/snippet}
{@render shelfBookCard(book)}
```

Fixed `ShelfSection` 3× `BookCard+ShelfActionMenu` duplication and `SettingsPanel` 8× tab buttons.

### 1.4 Props > singleton
Do not `import { appState } from '$lib/shared/stores'` inside a component. Pass a props bag.

```ts
// BAD — untestable without singleton mock
import { appState } from '$lib/shared/stores/AppState.svelte';
// ShelfSection 910L with 0 props

// GOOD — props-driven, 0 appState imports
type ShelfSectionProps = { books: Book[]; onCoverUpdated: (id: string) => void; /* ... */ };
```

`ShelfSection` 910L → 262L props-driven + `useShelfEdit` + `ShelfDetailModal`.

## 2. Architecture

- **Screaming:** `src/lib/features/*` vertical (library, reader, settings), `src/lib/shared/*` horizontal (ui, stores, types, api). If used by one feature, it lives in `features/`.
- **Barrel per feature:** `features/library/index.ts` exports public API; deep imports are a smell.
- **Ports for Tauri:** inject `invoke` as `deps: { persist: upsertSettings }` into composables — test without Tauri.
- **Shared/ui is presentational only.** `AppRouter`, `ShelfSection` belong to `features/` or `app/`, not `shared/ui/layout`.

## 3. Quality Gates

- **File budget:** component/composable <300L, facades <350L. Check with `wc -l`.
- **Work-unit commits:** one idea <400L per PR. P1: 5 PRs, P2: 5 PRs — stacked-to-main auto-chain.
- **Gates before push:** `bun run check` 0 errors, `rg '\$lib/(api|types|sync)'` 0 hits for shims, `rg 'appState\.'` 0 in new ShelfSection, `fd` for moved files.
- **Folder hygiene:** no `src/lib/graphify-out` inside `src` — use `/.graphify` + `.gitignore`. Legacy `src/lib/stores` is Auth-only (`authState`, `devicesState`, `toastQueue`); rest is `shared/stores`.

## 4. Testing

- **Standard Mode (`strict_tdd: false`):** tests only for non-trivial logic.
- **Pure logic = mandatory:** `clampPdfScale`, `resolveGenre` (80 chars/control-char), `flattenOutline`, `buildChapterSrcdoc` golden, `isValidSessionProgressEvent` (30s gate).
- **Regression harness:** `home.test.ts` 13/13 caught P2-C1. Keep `src/test/integration/home.test.ts` green.
- **Pre-commit:** `cd src-tauri && cargo check && cargo clippy -- -D warnings && cargo fmt --check && cargo deny check && bun run check && bun run lint && bun run format:check`.

## Reference

- P1: `desktop-god-components-p1` (Epub 2013→205L, Settings 1517→236L, 11 composables)
- P2: `desktop-god-components-p2` (Pdf 1184→232L, AppState 1001→413L, ReaderDomain 825→256L + ReaderSyncState 503L, ShelfSection 910→262L)

RDD is intentionally disabled. Enable only when team explicitly wants it: `gentle-ai review mode enable`.
