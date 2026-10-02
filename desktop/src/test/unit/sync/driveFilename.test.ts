/**
 * The shared contract: this test and the Rust (`filename.rs`) and Kotlin
 * (`DriveFilenameTest`) runners read the SAME
 * `packages/drive-filename-fixtures/fixtures.json`. A divergence fails the
 * platform that drifted instead of staying latent — INV-1 is executable.
 */
import { readFileSync, statSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  canonicalDriveObjectName,
  canonicalExtension,
  canonicalStem,
  legacyForms,
  legacyStateNames,
} from '$lib/shared/sync/driveFilename';

interface FilenameFixture {
  input: string;
  expectedStem: string;
}

function locateFixturesFile(): string {
  let dir = resolve(process.cwd());
  for (;;) {
    const candidate = join(dir, 'packages', 'drive-filename-fixtures', 'fixtures.json');
    try {
      statSync(candidate);
      return candidate;
    } catch {
      const parent = dirname(dir);
      if (parent === dir) throw new Error(`fixtures.json not found above ${process.cwd()}`);
      dir = parent;
    }
  }
}

const fixtures = JSON.parse(readFileSync(locateFixturesFile(), 'utf8')) as FilenameFixture[];

describe('DriveFilename — shared fixtures contract', () => {
  it('keeps at least 14 fixtures and matches each expected stem', () => {
    expect(fixtures.length).toBeGreaterThanOrEqual(14);
    for (const fixture of fixtures) {
      expect(canonicalStem(fixture.input), `canonical stem for ${fixture.input}`).toBe(
        fixture.expectedStem,
      );
      // Idempotence: re-canonicalizing an already-canonical stem is a no-op.
      expect(canonicalStem(fixture.expectedStem)).toBe(fixture.expectedStem);
    }
  });

  it('composes the canonical object name from stem and extension', () => {
    expect(canonicalDriveObjectName('gutendex:2701', 'EPUB')).toBe('gutendex2701.epub');
    expect(canonicalDriveObjectName('CON', null)).toBe('con_.epub');
    expect(canonicalExtension('.PDF')).toBe('pdf');
    expect(canonicalExtension('')).toBe('epub');
  });

  it('covers every divergent sanitizer form for a colon id', () => {
    const forms = legacyForms('gutendex:2701');
    expect(forms.has('gutendex2701')).toBe(true);
    expect(forms.has('gutendex-2701')).toBe(true);
    expect(forms.has('gutendex_2701')).toBe(true);
    expect(forms.has('gutendex:2701')).toBe(true);
  });

  it('recognizes the raw colon-bearing legacy state names', () => {
    const names = legacyStateNames('gutendex:2701');
    expect(names.has('gutendex2701_state.json')).toBe(true);
    expect(names.has('gutendex:2701_state.json')).toBe(true);
  });
});
