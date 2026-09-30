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
  },
});
