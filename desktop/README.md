# Nexo Desktop

Desktop shell for Nexo: a local-first book reader for EPUB and PDF files. Built with **Tauri 2** (Rust) and **Svelte 5** (TypeScript + Tailwind CSS v4). Books are stored and indexed in a local SQLite database, with optional cloud sync through Supabase and Drive-backed downloads.

## Requisitos previos

- [Bun](https://bun.sh) >= 1.3.0
- [Node.js](https://nodejs.org) >= 20
- [Rust](https://rustup.rs) >= 1.77 (edition 2021)
- Tauri system dependencies for Windows:
  - [Microsoft C++ Build Tools](https://visualstudio.microsoft.com/visual-cpp-build-tools/) (MSVC toolchain + Windows SDK)
  - WebView2 Runtime (comes with Windows 11 / most Windows 10 installs)

## Instalación

```bash
bun install
```

## Desarrollo

Run the frontend only (Vite dev server):

```bash
bun run dev
```

Run the full desktop app (frontend + Tauri with hot reload):

```bash
bun run tauri:dev
```

## Build

Build the desktop installer/bundle:

```bash
bun run tauri:build
```

Output artifacts land in `src-tauri/target/release/bundle/`.

## Scripts útiles

| Comando | Qué hace |
| --- | --- |
| `bun run dev` | Dev server del frontend |
| `bun run tauri:dev` | App desktop en modo desarrollo |
| `bun run tauri:build` | Compila el instalador |
| `bun run test` | Ejecuta los tests (Vitest) |
| `bun run check` | Typecheck (svelte-check) |
| `bun run lint` | ESLint |
| `bun run format:check` | Prettier check |
| `bun run rust:check` | Typecheck Rust (`cargo check`) |
| `bun run rust:lint` | Clippy (`cargo clippy -- -D warnings`) |
| `bun run ci` | Todos los checks en un solo comando |

## Estructura

- `src/` — Frontend Svelte 5 (`src/lib/components/`, `src/lib/stores/`, `src/lib/types/`, `src/lib/services/`)
- `src-tauri/` — Backend Rust/Tauri (`src-tauri/src/commands/`, `src-tauri/src/db.rs`)
- `src-tauri/migrations/` — Migraciones de la base SQLite

## Notas

- La app usa `bun` como package manager (no npm/yarn).
- Los comandos Rust asumen que tenés el toolchain de MSVC instalado (default en Windows con Rustup).
