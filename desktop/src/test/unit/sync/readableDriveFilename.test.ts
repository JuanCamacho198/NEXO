/**
 * New readable Drive object name contract: `<sanitized title> -- <id>.<ext>`.
 *
 * The identity-bearing id is retained after the separator so a readable file and
 * its id-only twin resolve to the SAME bookId. `parseCanonicalBookName` must read
 * BOTH shapes without breaking the original id-only format.
 */
import { describe, expect, it } from 'vitest';
import {
  canonicalExtension,
  canonicalStem,
  parseReadableStem,
  readableDriveObjectName,
  sanitizeTitleForFilename,
} from '$lib/shared/sync/driveFilename';
import { parseCanonicalBookName } from '$lib/shared/protocol/DriveCatalogContract';

const UUID = '5b4ba1c1-1b6f-4301-816a-40385be603fa';

describe('sanitizeTitleForFilename', () => {
  it('replaces the NTFS/Drive reserved characters with spaces and collapses whitespace', () => {
    expect(sanitizeTitleForFilename('a/b\\c:d*e?f"g<h>i|j')).toBe('a b c d e f g h i j');
    expect(sanitizeTitleForFilename('  multiple   spaces  ')).toBe('multiple spaces');
  });

  it('trims leading/trailing dots and spaces (NTFS rejects a trailing dot or space)', () => {
    expect(sanitizeTitleForFilename('Moby Dick. ')).toBe('Moby Dick');
    expect(sanitizeTitleForFilename('...title...')).toBe('title');
  });

  it('strips control characters', () => {
    expect(sanitizeTitleForFilename('line\nbreak\ttab')).toBe('line break tab');
  });

  it('keeps Unicode letters, case, and accents so the name is readable', () => {
    expect(sanitizeTitleForFilename('Prisioneros de la geografía')).toBe(
      'Prisioneros de la geografía',
    );
  });

  it('returns an empty string when nothing usable remains', () => {
    expect(sanitizeTitleForFilename('///')).toBe('');
    expect(sanitizeTitleForFilename('   ')).toBe('');
  });

  it('bounds on Unicode code points, never splitting a surrogate pair', () => {
    const title = `${'a'.repeat(79)}🎉tail`;
    const sanitized = sanitizeTitleForFilename(title);
    // 80 code points kept: the emoji sits exactly on the boundary and survives
    // whole, instead of leaving a lone surrogate (which `.slice(0,80)` would).
    expect(Array.from(sanitized).length).toBe(80);
    expect(sanitized.endsWith('🎉')).toBe(true);
    expect(sanitized).toBe(`${'a'.repeat(79)}🎉`);
    // No lone surrogate is ever produced.
    expect(
      /[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(sanitized),
    ).toBe(false);
  });
});

describe('readableDriveObjectName', () => {
  it('composes `<title> -- <id>.<ext>`', () => {
    expect(readableDriveObjectName('Prisioneros de la geografía', UUID, 'epub')).toBe(
      `Prisioneros de la geografía -- ${UUID}.epub`,
    );
  });

  it('falls back to the id-only canonical name for an empty title', () => {
    const idOnly = `${canonicalStem(UUID)}.epub`;
    expect(readableDriveObjectName('', UUID, 'epub')).toBe(idOnly);
    expect(readableDriveObjectName('///', UUID, 'epub')).toBe(idOnly);
  });

  it('uses the canonical extension, defaulting unknown formats to epub', () => {
    expect(readableDriveObjectName('Title', UUID, '').endsWith('.epub')).toBe(true);
    expect(readableDriveObjectName('Title', UUID, 'PDF').endsWith('.pdf')).toBe(true);
  });
});

describe('parseReadableStem', () => {
  it('recovers the id after the LAST separator', () => {
    expect(parseReadableStem(`Title -- ${UUID}`)).toBe(UUID);
    // A title that itself contains the separator still resolves to the id.
    expect(parseReadableStem(`A -- B -- ${UUID}`)).toBe(UUID);
  });

  it('returns null for a stem without a separator', () => {
    expect(parseReadableStem(UUID)).toBeNull();
    expect(parseReadableStem('Title')).toBeNull();
    expect(parseReadableStem('Title -- ')).toBeNull();
  });
});

describe('parseCanonicalBookName reads BOTH formats', () => {
  it('parses the original id-only format unchanged', () => {
    expect(parseCanonicalBookName(`${UUID}.epub`)).toEqual({ bookId: UUID, ext: 'epub' });
    expect(parseCanonicalBookName('book-1.PDF')).toEqual({ bookId: 'book-1', ext: 'pdf' });
  });

  it('parses the readable format back to the SAME bookId as the id-only twin', () => {
    const readable = readableDriveObjectName('Prisioneros de la geografía', UUID, 'epub');
    expect(parseCanonicalBookName(readable)).toEqual({ bookId: UUID, ext: 'epub' });
    // The two shapes dedupe to one book: identical parse result.
    expect(parseCanonicalBookName(readable)).toEqual(parseCanonicalBookName(`${UUID}.epub`));
  });

  it('still rejects state files and malformed names', () => {
    expect(parseCanonicalBookName(`${UUID}_state.json`)).toBeNull();
    expect(parseCanonicalBookName('state.json')).toBeNull();
    expect(parseCanonicalBookName('')).toBeNull();
    expect(parseCanonicalBookName('nodot')).toBeNull();
  });

  it('strips a Drive collision suffix on a readable id', () => {
    expect(parseCanonicalBookName(`Title -- ${UUID} (1).epub`)).toEqual({
      bookId: UUID,
      ext: 'epub',
    });
  });
});

describe('round-trip identity', () => {
  it('readableDriveObjectName -> parseCanonicalBookName is identity on the id', () => {
    for (const title of ['Prisioneros de la geografía', 'Moby Dick. ', '///', 'CON']) {
      const name = readableDriveObjectName(title, UUID, 'epub');
      expect(parseCanonicalBookName(name)?.bookId).toBe(UUID);
      expect(canonicalExtension(parseCanonicalBookName(name)?.ext)).toBe('epub');
    }
  });
});
