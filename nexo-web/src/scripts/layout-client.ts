// Single entry for the layout-level client behaviour. One module means one
// request and one cache entry instead of three near-empty chunks, which is what
// Astro emits when a page has several <script> imports.

import './theme-toggle';
import './lang-picker';
import './os-detect';
