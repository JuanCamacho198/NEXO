# Hero device renders

Re-renders `src/assets/hero/screen-{laptop,tablet,phone}-v2.png`: three Sketchfab
models with the app screens in `assets/` mapped onto their displays, lit warm
from behind to match the hero sky. The site ships only the resulting PNGs.

## Inputs

- `assets/screen-*.png` — the app's dark UI redrawn in `screens.html` with type
  ~1.6x larger than the app's, so it reads at the size the renders are shown
  (the Figma exports it replaced were legible only zoomed in). Never show an
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

Open the page in a browser (Playwright works) and run in its console. The hero
screens are `#screen-hero-laptop`, `#screen-hero-tablet` and `#screen-hero-phone`
in `screens.html` (they show real covers: copy `src/assets/covers/*.jpg` into a
git-ignored `covers/` next to it first), screenshotted by element into
`assets/screen-hero-*.png`. The tablet texture is stored rotated 270° because
the iPad model's own UVs run sideways; it uses those UVs (no `planar`), since a
planar projection shears this screen at the hero's angle.

```js
await renderDevice({
  file: 'laptop.glb',
  screen: { by: 'mat', value: 'ComputerScreen' },
  texture: 'screen-hero-laptop.png',
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
  texture: 'screen-hero-tablet.png',
  flipY: true,
  rotation: [-6, -28, -91],
  size: [1500, 1200],
  name: 'tablet',
  cam: { dist: 5.2, elev: 8, azim: 0 },
});
await renderDevice({
  file: 'iphone_16_-_free.glb',
  screen: { by: 'index', value: [3] },
  texture: 'screen-hero-phone.png',
  flipY: true,
  planar: { axes: [0, 1] },
  desat: 0.55,
  rotation: [-4, 22, 7],
  size: [800, 1300],
  name: 'phone',
  cam: { dist: 6.4, elev: 6, azim: 0 },
});
```

Then trim the transparent margins into the site assets (from `nexo-web/`):

```sh
bun -e 'const sharp = require("sharp"); for (const n of ["laptop","tablet","phone"]) await sharp(`tools/device-renders/out/${n}.png`).trim({ threshold: 1 }).png({ compressionLevel: 9 }).toFile(`src/assets/hero/screen-${n}-v2.png`);'
```

## Steps section ("Cómo funciona")

`src/assets/steps/step{1,2,3}-{a,b}.png` are two states per device that the
steps cards cross-fade between. Their screens are not Figma exports: they are
redrawn in `screens.html` (the app's dark UI with type ~1.6x larger, so it
reads at card size) and screenshotted to `assets/screen-step*.png` by element
(`#screen-step1-a` … `#screen-step3-b`), at deviceScaleFactor 2 for steps 1
and 2 (2880×1800 and 2732×2048) so the screens stay sharp on retina. Render
each pair with the same camera. Steps 1 and 2 were re-rendered larger, and the
tablet at a steeper angle with brighter metal so its edge reads as 3D:

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
    size: [2400, 1800],
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
    rotation: [-30, -30, -90],
    size: [2400, 1950],
    name,
    cam: { dist: 5.8, elev: 20, azim: 0 },
    envIntensity: 0.9,
    key: 3.6,
    rim: 3.4,
    fill: 1.0,
    front: 0.8,
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
separately, or state B will not land exactly on state A. Steps 1 and 2 are
then downscaled to 1600px wide.

## Hero books

`src/assets/hero/books/book-flip-{1..5}.png` are the floating books behind the
hero devices: five angles of one model, `assets/book.glb` (open, pages turning;
not committed, git-ignored like the devices). Its licence and author still need
recording here, and in the footer if it is CC-BY.

Lit as the hero sky is: a strong warm key from behind with self-shadowing, a
purple sky / orange ground hemisphere, almost no front light, so the faces
turned away from the sun fall into shade. The canvas is large on purpose so no
book touches its edge (a clipped edge shows as a cut-off book after the trim).

```js
const flip = (rotation, name) =>
  renderDevice({
    file: 'book.glb',
    colors: {
      'Material.004': '#4a2a1a',
      SIDES: '#c9ab82',
      paper: '#e6cfa8',
      'Material.002': '#e3caa2',
      'Material.003': '#e3caa2',
    },
    rotation,
    size: [1400, 1300],
    name,
    cam: { dist: 8.5, elev: 10 },
    key: 6.5,
    rim: 4.0,
    fill: 0.25,
    front: 0.08,
    envIntensity: 0.08,
    exposure: 0.95,
    shadows: true,
    hemi: [0x5a4a8a, 0xc0602a, 0.65],
  });
await flip([24, -28, -14], 'flip-1');
await flip([-30, 38, 16], 'flip-2');
await flip([38, 150, 10], 'flip-3');
await flip([-55, -120, -20], 'flip-4');
await flip([12, 70, -28], 'flip-5');
```

Trim and downscale to 480px wide (from `nexo-web/`):

```sh
bun -e 'const sharp = require("sharp"); for (const n of [1,2,3,4,5]) await sharp(await sharp(`tools/device-renders/out/flip-${n}.png`).trim({ threshold: 1 }).toBuffer()).resize({ width: 480 }).png({ compressionLevel: 9 }).toFile(`src/assets/hero/books/book-flip-${n}.png`);'
```
