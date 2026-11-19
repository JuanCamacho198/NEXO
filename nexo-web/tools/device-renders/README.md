# Hero device renders

Re-renders `src/assets/hero/device-{laptop,tablet,phone}.png`: three Sketchfab
models with the app screens in `assets/` mapped onto their displays, lit warm
from behind to match the hero sky. The site ships only the resulting PNGs.

## Inputs

- `assets/screen-*.png` — dark app screens exported from the NEXO-DESIGN Figma
  file (`Lector / La Odisea 1440x900 DARK (resaltados)` → laptop, `… (Texto)` →
  tablet, Android `Lectura del Libro` → phone). Never use screens that show an
  account name or email.
- `assets/laptop.glb`, `assets/ipad_pro_12.9_2020.glb`,
  `assets/iphone_16_-_free.glb` — not committed (git-ignored):
  - [Laptop](https://sketchfab.com/3d-models/laptop-7d870e900889481395b4a575b9fa8c3e)
    by Aullwen, CC-BY-4.0
  - [iPad Pro 12.9 (2020)](https://sketchfab.com/3d-models/ipad-pro-129-2020-f0f7674522124f3bbc2d0f898963457e)
    by Konstantin Koretskyi, CC-BY-4.0
  - [iPhone 16 - Free](https://sketchfab.com/3d-models/iphone-16-free-d58591e88a824dfd8cef0af616273b02)
    by Wes, Sketchfab Standard (no credit required)

  The two CC-BY models must stay credited in the site footer
  (`SiteFooter.astro`) for as long as their renders ship.

## Run

```sh
cd nexo-web/tools/device-renders
bun add --no-save three@0.170.0
bun server.mjs           # http://127.0.0.1:4390, writes to out/
```

Open the page in a browser (Playwright works) and run in its console:

```js
await renderDevice({
  file: 'laptop.glb',
  screen: { by: 'mat', value: 'ComputerScreen' },
  texture: 'screen-laptop.png',
  flipY: true,
  planar: { flipU: true },
  pad: [0.02, 0.07, 0.02, 0.06],
  grey: ['ComputerFrame'],
  greyFilter: 'grayscale(1) brightness(0.7) contrast(1.1)',
  key: 1.4,
  front: 1.1,
  fill: 1.1,
  rotation: [0, 20, 0],
  size: [1800, 1300],
  name: 'laptop',
  cam: { dist: 5.9, elev: 11, azim: 0 },
});
await renderDevice({
  file: 'ipad_pro_12.9_2020.glb',
  screen: { by: 'mat', value: 'screen' },
  texture: 'screen-tablet.png',
  flipY: true,
  planar: {},
  rotation: [-6, -28, -91],
  size: [1500, 1200],
  name: 'tablet',
  cam: { dist: 5.2, elev: 8, azim: 0 },
});
await renderDevice({
  file: 'iphone_16_-_free.glb',
  screen: { by: 'index', value: [3] },
  texture: 'screen-phone.png',
  flipY: true,
  planar: { axes: [0, 1] },
  pad: [0, 0.06, 0, 0],
  padColor: '#0d1324',
  desat: 0.55,
  rotation: [-4, 22, 7],
  size: [800, 1300],
  name: 'phone',
  cam: { dist: 6.4, elev: 6, azim: 0 },
});
```

Then trim the transparent margins into the site assets (from `nexo-web/`):

```sh
bun -e 'const sharp = require("sharp"); for (const n of ["laptop","tablet","phone"]) await sharp(`tools/device-renders/out/${n}.png`).trim({ threshold: 1 }).png({ compressionLevel: 9 }).toFile(`src/assets/hero/device-${n}.png`);'
```

## Steps section ("Cómo funciona")

`src/assets/steps/step{1,2,3}-{a,b}.png` are two states per device that the
steps cards cross-fade between. Their screens are not Figma exports: they are
redrawn in `screens.html` (the app's dark UI with type ~1.6x larger, so it
reads at card size) and screenshotted to `assets/screen-step*.png` by element
(`#screen-step1-a` … `#screen-step3-b`). Render each pair with the same camera:

```js
const laptop = (tex, name) =>
  renderDevice({
    file: 'laptop.glb',
    screen: { by: 'mat', value: 'ComputerScreen' },
    texture: tex,
    flipY: true,
    planar: { flipU: true },
    pad: [0.02, 0.07, 0.02, 0.06],
    grey: ['ComputerFrame'],
    greyFilter: 'grayscale(1) brightness(0.7) contrast(1.1)',
    key: 1.4,
    front: 1.1,
    fill: 1.1,
    rotation: [0, -14, 0],
    size: [1600, 1200],
    name,
    cam: { dist: 5.6, elev: 12, azim: 0 },
  });
const tablet = (tex, name) =>
  renderDevice({
    file: 'ipad_pro_12.9_2020.glb',
    screen: { by: 'mat', value: 'screen' },
    texture: tex,
    flipY: true,
    planar: {},
    rotation: [-4, -16, -90],
    size: [1500, 1200],
    name,
    cam: { dist: 5.0, elev: 8, azim: 0 },
  });
const phone = (tex, name) =>
  renderDevice({
    file: 'iphone_16_-_free.glb',
    screen: { by: 'index', value: [3] },
    texture: tex,
    flipY: true,
    planar: { axes: [0, 1] },
    desat: 0.55,
    rotation: [-3, 18, 4],
    size: [900, 1400],
    name,
    cam: { dist: 6.2, elev: 6, azim: 0 },
  });
```

Trim each pair with one shared box (the union of both trims), never
separately, or state B will not land exactly on state A.
