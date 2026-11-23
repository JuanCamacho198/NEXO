# Nexo brand assets

Source of truth: `source/NEXO logos light.svg` and `source/NEXO logos dark.svg`
(original sheets, untouched). Mark: a warm-paper tile with a gradient bookmark
ribbon and an "N" knocked out of it, plus the "NEXO" wordmark ("N" lives in the
tile, so the letters read "EXO").

## Files

| File | Lockup | Use |
| --- | --- | --- |
| `nexo-logo-inverso.svg` | tile + ribbon + "N", square 512×512 | Android launcher icon, favicon, splash |
| `nexo-mark-only.svg` | ribbon + "N" on transparency, 120% of the square | desktop icon set |
| `nexo-logo-horizontal.svg` | the square plus the "EXO" wordmark beside it | site header, footers, in-app welcome lockup |

Both exports have transparent backgrounds and no width/height attributes beyond
their own canvas, so they scale freely.

## Rules

- **Masked surfaces keep the tile.** On Android the adaptive icon's `background`
  is the plate and the ribbon is its `foreground`, scaled to 78% so the head and
  tail stay inside the 66/108 safe area on every mask shape. Shipping the ribbon
  alone there fills the mask and reads as an oversized glyph. The plate is flat
  white, not the tile's gradient: the gradient's peach end read as an orange
  background. See `scripts/brand/Sync-AndroidBrandDrawables.ps1`.
- **The desktop icon set ships the mark alone.** Windows and macOS draw the icon
  as it is and never mask it, so the tile has nothing to do there:
  `nexo-mark-only.svg` is the ribbon on transparency at 120%. The export insets
  the ribbon inside the tile, so on its own it fills only 77% of the canvas;
  scaling past 100% brings it to 93% tall, which is what makes it read at the
  same size as the square icons beside it in a taskbar.
- **The splash is the same composite**, at 52% instead of 78%: the platform
  masks a splash icon to a circle 2/3 of the drawable, so the same apparent size
  needs a smaller scale.
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
| Desktop icon set (`desktop/src-tauri/icons`) | `nexo-mark-only.svg` | from `desktop/`: `bunx tauri icon ../brand/nexo-mark-only.svg`, then delete the `icons/android` and `icons/ios` folders it also writes |
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
