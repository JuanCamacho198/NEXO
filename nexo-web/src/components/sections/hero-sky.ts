// Single source of truth for the landing's LCP image. Both the <link rel=preload>
// in Layout and the <Picture> in Hero import from here, because a preload whose
// imagesrcset does not match the element's srcset makes the browser fetch a
// second candidate instead of reusing the first.

import heroSky from '@/assets/hero/sky.png';
import { getImage } from 'astro:assets';

const HERO_SKY_WIDTHS = [640, 960, 1280, 1470] as const;
const HERO_SKY_SIZES = '100vw';
const HERO_SKY_QUALITY = 72;

const avif = await Promise.all(
  HERO_SKY_WIDTHS.map(async (width) => ({
    url: (await getImage({ src: heroSky, width, format: 'avif', quality: HERO_SKY_QUALITY })).src,
    width,
  })),
);

export const heroSkyPreload = {
  srcset: avif.map((entry) => `${entry.url} ${entry.width}w`).join(', '),
  sizes: HERO_SKY_SIZES,
};
export { heroSky, HERO_SKY_WIDTHS, HERO_SKY_SIZES, HERO_SKY_QUALITY };
