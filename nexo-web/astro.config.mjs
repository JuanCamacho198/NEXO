import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  // Static output plus an explicit site: the site URL activates canonical URLs and
  // the hreflang alternates in Layout.astro, which stay conditional until a real
  // domain is configured. trynexo.app is the production custom domain attached to
  // the nexo-web Worker, and the homepage registered on the Google OAuth consent screen.
  output: 'static',
  site: 'https://trynexo.app',
  i18n: {
    defaultLocale: 'es',
    locales: ['es', 'en'],
    routing: { prefixDefaultLocale: false },
  },
  vite: {
    plugins: [tailwindcss()],
    build: {
      // Defaults to 'esnext', which emits the bundled scripts INLINE in the
      // HTML. An external chunk is the whole point of moving the client code
      // out of <script is:inline>: it gets a content hash, so the Worker serves
      // it with a long-lived immutable cache and a repeat visitor downloads
      // nothing. Lowering the target is what makes Vite treat the entry as a
      // separate asset.
      target: 'es2020',
      assetsInlineLimit: 0,
    },
  },
});
