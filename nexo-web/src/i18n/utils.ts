import { en } from '@/i18n/en';
import { es } from '@/i18n/es';

export const defaultLang = 'es';
export const languages = { es: 'Español', en: 'English' } as const;
export type Lang = keyof typeof languages;

export const ui = { es, en } as const;
export type UIKey = keyof (typeof ui)[typeof defaultLang];

/**
 * One static path per locale for a `[...locale]` catch-all route. The default
 * locale renders at the bare path (`locale: undefined`), every other locale at
 * its prefix (`locale: 'en'`). Adding a language means adding it to
 * `languages` above — no new route file.
 */
export function localeStaticPaths(): { params: { locale: string | undefined } }[] {
  return (Object.keys(languages) as Lang[]).map((lang) => ({
    params: { locale: lang === defaultLang ? undefined : lang },
  }));
}

export function getLangFromUrl(url: URL): Lang {
  const first = url.pathname.split('/')[1];
  if (first && first in languages) return first as Lang;
  return defaultLang;
}

export function useTranslations(lang: Lang): (key: UIKey) => string {
  const dictionary = ui[lang] as Record<UIKey, string>;
  const fallback = ui[defaultLang] as Record<UIKey, string>;
  return (key) => dictionary[key] ?? fallback[key];
}

export function localizePath(path: string, lang: Lang): string {
  // External URLs and bare fragments are language-agnostic; leave them untouched.
  if (/^([a-z][a-z0-9+.-]*:|\/\/)/i.test(path) || path.startsWith('#')) {
    return path;
  }

  // Split off query string and hash so they are preserved verbatim.
  const match = path.match(/^([^?#]*)(\?[^#]*)?(#.*)?$/);
  const pathname = match?.[1] ?? '';
  const query = match?.[2] ?? '';
  const hash = match?.[3] ?? '';

  const base = pathname === '' ? '/' : pathname;

  if (lang === defaultLang) {
    return `${base}${query}${hash}`;
  }

  const localized = base === '/' ? '/en/' : `/en${base}`;
  return `${localized}${query}${hash}`;
}
