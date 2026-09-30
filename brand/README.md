# Nexo brand assets

Source of truth: `source/NEXO logos light.svg` and `source/NEXO logos dark.svg`
(original sheets, untouched). Brand color: `#0D1D3B` (deep navy). Mark: bookmark
ribbon with "N" + wordmark "NEXO".

All exported SVGs have transparent backgrounds, tight viewBoxes, and no
width/height attributes (scale freely).

## Files

| File | Lockup | Theme | Use |
| --- | --- | --- | --- |
| `nexo-icon-light.svg` | icon only | light | favicons, avatars, small sizes on light UIs |
| `nexo-icon-dark.svg` | icon only | dark | favicons, avatars, small sizes on dark UIs |
| `nexo-vertical-light.svg` | icon + NEXO below | light | centered headers, splash screens on light UIs |
| `nexo-vertical-dark.svg` | icon + NEXO below | dark | centered headers, splash screens on dark UIs |
| `nexo-horizontal-light.svg` | icon + NEXO beside | light | site header, footers, documents on light UIs |
| `nexo-horizontal-dark.svg` | icon + NEXO beside | dark | site header, footers, documents on dark UIs |
| `nexo-app-light.svg` | rounded-square app icon | light | app icon, store listings on light UIs |
| `nexo-app-dark.svg` | rounded-square app icon | dark | app icon, store listings on dark UIs |

## Rules

- Never recolor the mark outside these exports; do not add gradients or shadows.
- Keep clear space around the logo of at least the width of the ribbon's stem.
- Pick the export matching the background: `-light` on light surfaces,
  `-dark` on dark surfaces. Never place a `-light` export on a dark background
  or vice versa.
- Exception to the rule above: on a surface that IS the brand navy `#0D1D3B`,
  the `-dark` exports do not read — their ribbon ink is `#0D1D3B`, so it
  disappears and only the grey "N" (and any white wordmark) survives. Verified
  by rendering all eight exports over magenta versus navy: 6 of 8 lose at least
  one element on `#0D1D3B`. On brand-navy surfaces use `nexo-app-dark.svg`,
  whose tile coincides with the surface and whose white mark and wordmark carry
  all the information.
- Minimum size for the horizontal lockup: 96px wide.

## Regenerating

Exports are extracted from the sheets in `source/`: drop the full-canvas
background rect, group the elements of each lockup (vertical, app, horizontal,
icon), and write each group with a tight viewBox and no width/height.
