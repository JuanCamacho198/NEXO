/**
 * Short source attribution for a catalog book.
 *
 * Provider ids arrive namespaced (`builtin:gutendex`, `addon:<slug>`). The raw id
 * is an internal detail and must never reach the UI: every `builtin:` id resolves
 * to a human label, and an unmapped one is humanized rather than printed verbatim.
 * Proper source names (Gutenberg, Open Library, Google Books) stay out of i18n on
 * purpose — they are brand names, not translated copy.
 */
export function providerLabel(provider: string): string {
  if (provider === 'builtin:gutendex') return 'Gutenberg';
  if (provider === 'builtin:openlibrary') return 'Open Library';
  if (provider === 'builtin:googlebooks') return 'Google Books';
  if (provider === 'curated') return 'Curated';
  if (provider.startsWith('addon:')) return 'Add-on';
  if (provider.startsWith('builtin:')) return humanize(provider.slice('builtin:'.length));
  return provider;
}

/** `some-new-source` / `foo_bar` -> `Some New Source` / `Foo Bar`. */
function humanize(id: string): string {
  return id
    .split(/[-_\s]+/)
    .filter((part) => part !== '')
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(' ');
}
