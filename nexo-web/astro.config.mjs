import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';

export default defineConfig({
  // Static output plus an explicit site: the site URL activates canonical URLs and
  // the hreflang alternates in Layout.astro, which stay conditional until a real
  // domain is configured. This is the account's default Workers subdomain; point it
  // at the custom domain when one is attached.
  output: 'static',
  site: 'https://nexo-web.jcamachomolina503.workers.dev',
  i18n: {
    defaultLocale: 'es',
    locales: ['es', 'en'],
    routing: { prefixDefaultLocale: false },
  },
  vite: {
    plugins: [tailwindcss()],
  },
});
