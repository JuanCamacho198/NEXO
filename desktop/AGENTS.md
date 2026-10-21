# AGENTS.md

## Commands

- **Install**: `bun install`
- **Dev frontend**: `bun run dev`
- **Dev Tauri**: `bun run tauri:dev`
- **Dev Tauri (CDP debug)**: `bun run tauri:dev:debug` — same app, with the WebView2 remote-debugging port open for the `tauri-cdp` Playwright project. Windows only, not a CI gate. See `e2e/tauri/README.md`.
- **Build frontend**: `bun run build`
- **Build Tauri**: `bun run tauri:build`
- **Test all**: `bun run test`
- **Test single**: `bun vitest run --testNamePattern="test name" src/test/file.test.ts`
- **Test e2e (stubbed, CI)**: `bun run test:e2e`
- **Test e2e (real app over CDP)**: `bun run test:e2e:cdp` — requires `tauri:dev:debug` running. Local only; see `e2e/tauri/README.md`.
- **Type check**: `bun run check`
- **Lint frontend**: `bun run lint`
- **Format frontend**: `bun run format`
- **Format check frontend**: `bun run format:check`

## Rust Tooling

- **Rust typecheck**: `cd src-tauri && cargo check`
- **Rust lint (Clippy)**: `cd src-tauri && cargo clippy -- -D warnings`
- **Rust format**: `cd src-tauri && cargo fmt`
- **Rust format check**: `cd src-tauri && cargo fmt --check`
- **Rust audit (deny)**: `cd src-tauri && cargo deny check`

## Project Structure

- **Frontend (Svelte 5)**: `src/`
  - **Canonical**: `src/lib/features/*` + `src/lib/shared/*` ( +legacy `src/lib/stores` Auth-only: `authState`, `devicesState`, `toastQueue`)
  - Features: `src/lib/features/reader/viewer-epub/`, `src/lib/features/settings/components/`, `src/lib/features/storage/components/`, `src/lib/features/sync/components/`, `src/lib/features/library/`, `src/lib/features/highlights/`
  - Shared: `src/lib/shared/stores/` (`AppState`, `SettingsDomainState`, `storageState`), `src/lib/shared/types/`, `src/lib/shared/services/`, `src/lib/shared/i18n/`, `src/lib/shared/ui/` (`shared/ui/layout` chrome-only)
  - Tests: `src/test/` (unit/, integration/)
- **Backend (Rust/Tauri)**: `src-tauri/src/`
  - Commands: `src-tauri/src/commands/`
  - DB: `src-tauri/src/db.rs`
- **Database migrations**: `src-tauri/migrations/`
- **Configs**: `src-tauri/Cargo.toml`, `vite.config.ts`, `vitest.config.ts`, `tsconfig.json`
  - ESLint: `eslint.config.js`
  - Prettier: `.prettierrc`
  - EditorConfig: `.editorconfig`
  - cargo-deny: `src-tauri/deny.toml`
  - rustfmt: `src-tauri/.rustfmt.toml`

## Engineering Standards

- **Engineering Standards**: see .docs/ENGINEERING-STANDARDS.md — 4 Svelte 5 commandments ($derived/$effect, .svelte.ts, snippets, props>singleton), architecture (features/shared, ports), quality gates (wc <300, rg, check). RDD REVIEW IS DISABLED — manual review only.

## Code Style

- **Svelte 5**: Use runes (`$state`, `$derived`, `$effect`) for reactive state
- **Tailwind CSS v4**: Always use Tailwind for styling. Do NOT use `<style>` blocks in components.
  - **Exceptions** (allowed in global CSS): `@keyframes` animations, `::-webkit-slider-thumb` / `::-moz-range-thumb` pseudo-elements for range inputs. Move these to `src/lib/styles/tokens.css`.
  - **Canonical v4 syntax for CSS variables**: Use `border-(--color-border)` NOT `border-[var(--color-border)]`. In Tailwind v4, drop the `var()` wrapper. Examples:
    - ✅ `border-(--color-border)` ❌ `border-[var(--color-border)]`
    - ✅ `text-(--color-primary)` ❌ `text-[var(--color-primary)]`
    - ✅ `bg-(--color-surface)` ❌ `bg-[var(--color-surface)]`
    - ✅ `bg-(--color-primary)/12` ❌ `bg-[var(--color-primary)]/12`
    - ✅ `text-(--color-text-muted,#6b7280)` ❌ `text-[var(--color-text-muted,#6b7280)]`
    - ✅ `hover:bg-(--color-border)` ❌ `hover:bg-[color:var(--color-border)]`
  - **Renamed classes in v4**: `break-words` → `wrap-break-word`, `flex-shrink-0` → `shrink-0`
  - **Arbitrary values with canonical names**: `min-w-[86px]` → `min-w-21.5`, `duration-[420ms]` → `duration-420`
  - **CSS conflictos**: NO pongas `flex` y `hidden` en el mismo elemento (usa `max-lg:hidden lg:flex`). NO combines `font-(--font-sans)` con `font-medium` (usa inline `style="font-family: var(--font-sans)"` para la font-family).
  - **Auto-detection**: Run `bun run lint` — the custom rule `local-rules/tailwind-v4-canonical` (in `eslint-local-rules/`) detects old syntax as warnings and can auto-fix with `bun run lint:fix`.
- **Imports**: Use `$lib` alias (e.g., `import { something } from '$lib/stores'`)
- **Types**: TypeScript with explicit return types on functions
- **Tests**: Vitest with `@testing-library/svelte` and jsdom
- **Formatting**: Single quotes, semicolons, trailing commas, 100 char width (Prettier)
- **Indentation**: 2 spaces for TS/Svelte, 4 spaces for Rust/Markdown

## Conventions

- State files: `*State.svelte.ts` naming convention
- Components: PascalCase `.svelte` files
- State classes: `svelte.ts` suffix
- Tests: `.test.ts` or `.test.svelte.ts` in `src/test/`
- Rust error handling: Use `thiserror` crate in `src-tauri/src/error.rs`
- Rust style: 4-space tabs, 100 char width (rustfmt)

## Pre-commit Checklist

Before committing, run:
1. `cd src-tauri && cargo check` (Rust typecheck)
2. `cd src-tauri && cargo clippy -- -D warnings` (Rust lint)
3. `cd src-tauri && cargo fmt --check` (Rust format)
4. `cd src-tauri && cargo deny check` (Rust security audit)
5. `bun run check` (Svelte typecheck)
6. `bun run lint` (ESLint)
7. `bun run format:check` (Prettier)

## Safety

- Never commit secrets; use `.env` and exclude from git
- Tauri commands run in Rust backend; validate all input in `commands.rs`


