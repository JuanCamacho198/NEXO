import { describe, it, expect, vi, beforeEach } from 'vitest';

vi.mock('$lib/shared/services/epubImportMetadata', () => ({
  extractEpubMetadataFromBytes: vi.fn(),
}));

import { extractEpubMetadataFromBytes } from '$lib/shared/services/epubImportMetadata';
import {
  isPlaceholderBookTitle,
  extractRestoredBookMeta,
} from '$lib/shared/services/restoredBookMetadata';

const extractMock = vi.mocked(extractEpubMetadataFromBytes);

describe('isPlaceholderBookTitle', () => {
  it('recognises the FK guard placeholder, including a shortened id suffix', () => {
    expect(isPlaceholderBookTitle('Unknown Title (sync stub) 5b4b')).toBe(true);
  });

  it('recognises the placeholder with surrounding whitespace', () => {
    expect(isPlaceholderBookTitle('  Unknown Title (sync stub) 5b4b  ')).toBe(true);
  });

  it('rejects a real title', () => {
    expect(isPlaceholderBookTitle('Prisioneros de la geografía')).toBe(false);
  });

  it('rejects null and undefined', () => {
    expect(isPlaceholderBookTitle(null)).toBe(false);
    expect(isPlaceholderBookTitle(undefined)).toBe(false);
  });
});

describe('extractRestoredBookMeta', () => {
  const bytes = new Uint8Array([1, 2, 3]);

  beforeEach(() => {
    extractMock.mockReset();
  });

  it('leaves a healthy row alone without ever parsing the file', async () => {
    const result = await extractRestoredBookMeta(
      { title: 'Prisioneros de la geografía', format: 'epub' },
      bytes,
    );

    expect(result).toBeNull();
    expect(extractMock).not.toHaveBeenCalled();
  });

  it('reads real metadata for a placeholder row', async () => {
    extractMock.mockResolvedValue({
      title: 'Prisioneros de la geografía',
      author: 'Some Author',
      subject: null,
      subjects: [],
    });

    const result = await extractRestoredBookMeta(
      { title: 'Unknown Title (sync stub) 5b4b', format: 'epub' },
      bytes,
    );

    expect(result).toEqual({
      title: 'Prisioneros de la geografía',
      author: 'Some Author',
      format: 'epub',
    });
  });

  it('offers only the fields the file actually carries', async () => {
    extractMock.mockResolvedValue({
      title: 'Prisioneros de la geografía',
      author: null,
      subject: null,
      subjects: [],
    });

    const result = await extractRestoredBookMeta(
      { title: 'Unknown Title (sync stub) 5b4b', format: 'epub' },
      bytes,
    );

    expect(result).toEqual({ title: 'Prisioneros de la geografía', format: 'epub' });
    expect(result).not.toHaveProperty('author');
  });

  it('returns null when the file yields no usable metadata', async () => {
    extractMock.mockResolvedValue({ title: null, author: null, subject: null, subjects: [] });

    const result = await extractRestoredBookMeta(
      { title: 'Unknown Title (sync stub) 5b4b', format: 'epub' },
      bytes,
    );

    expect(result).toBeNull();
  });

  it('returns null instead of throwing when parsing fails', async () => {
    extractMock.mockRejectedValue(new Error('broken epub'));

    const result = await extractRestoredBookMeta(
      { title: 'Unknown Title (sync stub) 5b4b', format: 'epub' },
      bytes,
    );

    expect(result).toBeNull();
  });

  it('does not attempt to parse a non-epub placeholder', async () => {
    const result = await extractRestoredBookMeta(
      { title: 'Unknown Title (sync stub) 5b4b', format: 'pdf' },
      bytes,
    );

    expect(result).toBeNull();
    expect(extractMock).not.toHaveBeenCalled();
  });
});
