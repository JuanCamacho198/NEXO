# Nexo brand assets

Source of truth: `source/NEXO logos light.svg` and `source/NEXO logos dark.svg`
(original sheets, untouched). Mark: a warm-paper tile with a gradient bookmark
ribbon and an "N" knocked out of it, plus the "NEXO" wordmark ("N" lives in the
tile, so the letters read "EXO").

## Files

| File | Lockup | Use |
| --- | --- | --- |
| `nexo-logo-inverso.svg` | tile + ribbon + "N", square 512×512 | app icon, launcher icon, favicon, splash |
| `nexo-logo-horizontal.svg` | the square plus the "EXO" wordmark beside it | site header, footers, in-app welcome lockup |

Both exports have transparent backgrounds and no width/height attributes beyond
their own canvas, so they scale freely.

## Rules

- **The tile is the app icon.** The tile is the icon body; a launcher mask cuts
  its corners and that is intended. Do not ship the ribbon alone as an icon: it
  fills the mask and reads as an oversized glyph.
- **Only the wordmark flips with the theme.** For light surfaces the letters are
  navy `#0D1D3B`; for dark surfaces they are cream `#FFF6EC`. The tile, the
  ribbon and the knocked-out "N" keep their brand colours in both themes — the
  "N" sits on the orange ribbon, never on the page surface, so it must NOT be
  recoloured with the wordmark.
- Do not recolour the mark outside these two exports and do not add shadows.
- Keep clear space around the lockup of at least the width of the ribbon's stem.

## Consumers, and how each one gets the art

| Surface | Source | Regenerate with |
| --- | --- | --- |
| Desktop icon set (`desktop/src-tauri/icons`) | `nexo-logo-inverso.svg` | `bunx tauri icon brand/nexo-logo-inverso.svg` |
| Desktop in-app lockup (`desktop/public`) | both exports | copy `nexo-logo-horizontal.svg` (dark) and its navy-ink variant (light) |
| Desktop favicon (`desktop/public/favicon.svg`) | `nexo-logo-inverso.svg` | hand-built wrapper, one branch per `prefers-color-scheme` |
| Android launcher/splash/monochrome | `nexo-logo-inverso.svg` | `scripts/brand/Sync-AndroidBrandDrawables.ps1` |
| Android in-app lockup (`NexoBrandLockup.kt`) | both horizontal exports | `svg-vectordrawable --format compose` |

## Tooling

`svg-vectordrawable` (CLI `svgvd`) does the SVG → Android VectorDrawable and SVG
→ Compose `ImageVector` translation. Both Vectors require gradients to be
declared inline as `<aapt:attr>` blocks, and Compose requires them as `Brush`;
a converter that writes the SVG spelling (`fill="url(#id)"`) produces a file
that compiles cleanly and renders nothing. Always convert with this tool rather
than by hand.
