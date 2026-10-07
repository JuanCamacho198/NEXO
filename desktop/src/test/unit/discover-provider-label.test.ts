import { describe, expect, it } from 'vitest';
import { providerLabel } from '$lib/features/discover/discoverProviderLabel';

describe('providerLabel', () => {
  it('maps every known provider id to its brand label', () => {
    expect(providerLabel('builtin:gutendex')).toBe('Gutenberg');
    expect(providerLabel('builtin:openlibrary')).toBe('Open Library');
    expect(providerLabel('builtin:googlebooks')).toBe('Google Books');
    expect(providerLabel('curated')).toBe('Curated');
    expect(providerLabel('addon:some-slug')).toBe('Add-on');
  });

  it('humanizes an unknown builtin source instead of printing the raw id', () => {
    expect(providerLabel('builtin:some-new-source')).toBe('Some New Source');
    expect(providerLabel('builtin:foo_bar')).toBe('Foo Bar');
  });

  it('never returns a string containing the builtin: namespace', () => {
    const ids = [
      'builtin:gutendex',
      'builtin:openlibrary',
      'builtin:googlebooks',
      'builtin:some-new-source',
      'builtin:unknown',
      'curated',
      'addon:some-slug',
      '',
    ];
    for (const id of ids) {
      expect(providerLabel(id)).not.toContain('builtin:');
    }
  });
});
