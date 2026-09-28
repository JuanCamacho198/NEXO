// Catalog seed data lives in ./addons.json — the single source of truth shared
// by Astro components and the prebuild emitter (scripts/emit-surfaces.mjs), which
// reads the JSON directly with JSON.parse.
//
// JSON strings do not narrow to the literal unions below, so the import is
// asserted. The cast erases compile-time checking: `astro check` cannot see
// through it, and the emitter only requires a non-empty array. Runtime schema
// validation of this file lives in ./addons.spec.ts and is enforced in CI by
// `bun run --cwd web test`. The value is not silently widened: consumers still
// see `AddonSeed[]`.
import addonsData from '@/data/addons.json';

export type AddonKind = 'builtin';
export type AddonAvailability = 'builtin' | 'community' | 'planned';
export type AddonCategory =
  'dominio-publico' | 'bibliotecas' | 'audiolibros' | 'wikis' | 'prensa' | 'independientes';
export type AddonLang = 'es' | 'en' | 'multi';

export interface AddonManifestRef {
  id: string;
  name: string;
  version: string;
  catalogs: Array<{ type: string; id: string; name: string }>;
  resources: string[];
}

export interface AddonSeed extends AddonManifestRef {
  author: string;
  license: string;
  languages: AddonLang[];
  category: AddonCategory;
  kind: AddonKind;
  availability: AddonAvailability;
  updatedAt: string;
  icon: 'book' | 'library' | 'sparkles' | 'headphones' | 'globe' | 'archive';
  installUrl: string | null;
  description: { es: string; en: string };
}

export const ADDONS = addonsData as unknown as AddonSeed[];

export function getAddon(id: string): AddonSeed | undefined {
  return ADDONS.find((a) => a.id === id);
}

export function addonDetailHref(locale: 'es' | 'en', id: string): string {
  return locale === 'es' ? `/catalogo/${id}` : `/en/catalog/${id}`;
}

export function catalogHref(locale: 'es' | 'en'): string {
  return locale === 'es' ? '/catalogo' : '/en/catalog';
}
