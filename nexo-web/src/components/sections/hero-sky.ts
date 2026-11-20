// Single source of truth for the landing's LCP image. Both the <link rel=preload>
// in Layout and the <Picture> in Hero read from here, because a preload whose
// imagesrcset does not match the element's srcset makes the browser fetch a
// second candidate instead of reusing the first.
//
// Deliberately synchronous: a top-level `await` here is fine in an .astro
// frontmatter but not for a module Vite has to inline into a page at a lowered
// build target, and it only ever earned a warning. The preload srcset is built
// from the same literal widths the <Picture> is given, so the two stay in step
// without asking astro:assets to resolve each URL a second time.

import heroSky from '@/assets/hero/sky.png';

export const HERO_SKY_WIDTHS = [640, 960, 1280, 1470];
export const HERO_SKY_SIZES = '100vw';
export const HERO_SKY_QUALITY = 72;
export { heroSky };
