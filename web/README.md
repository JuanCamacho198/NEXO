# NEXO Web

Public web surface for NEXO: a static Astro 5 site with ES-first i18n and
the design tokens shared with the desktop app.

## Local development

Install once from the repo root (single Bun workspace lock):

```sh
bun install
```

Then run the dev server:

```sh
bun run --cwd web dev
```

## Build

```sh
bun run --cwd web build
```

Output directory: `web/dist/` (fully static, no adapter).

## Deployment (manual for v1)

There is no CI deploy: deploys to Cloudflare Pages are triggered manually from
the Cloudflare console for v1.

1. In the Cloudflare console, create a **Pages** project and connect this
   repository.
2. Build command: `bun run --cwd web build`
3. Build output directory: `web/dist`
4. Environment variables: none (the site is fully static).
5. Bind the site's custom domain (apex) and add `www` with a redirect to the
   apex. No apex domain is decided for NEXO yet, so the deployer picks it.

## Known gaps

- `/catalogo`, `/enviar`, and `/docs` are linked from the Nav but do not exist
  yet (they arrive in changes 2–3), so those links 404 for now.
