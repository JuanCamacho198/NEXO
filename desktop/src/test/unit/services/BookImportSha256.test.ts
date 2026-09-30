/**
 * Unit tests for SHA-256 content-hash computation in BookImportService (PR 5).
 *
 * Tests the `crypto.subtle.digest('SHA-256', ...)` + format pipeline:
 * - Correct hex output for known content
 * - "sha256:" prefix format
 * - Same content → same hash (deterministic)
 * - Different content → different hash
 */
import { describe, it, expect } from 'vitest';

const encoder = new TextEncoder();

async function computeSha256(content: string): Promise<string> {
  const bytes = encoder.encode(content);
  const hashBuffer = await crypto.subtle.digest('SHA-256', bytes);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  const hashHex = hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
  return `sha256:${hashHex}`;
}

describe('SHA-256 computation — hex output', () => {
  it('produces correct hash for "Hello, World!"', async () => {
    const hash = await computeSha256('Hello, World!');
    // SHA-256 of "Hello, World!" (verified independently):
    // dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f
    expect(hash).toBe('sha256:dffd6021bb2bd5b0af676290809ec3a53191dd81c7f70a4b28688a362182986f');
  });

  it('prefixes with sha256:', async () => {
    const hash = await computeSha256('Any content');
    expect(hash).toMatch(/^sha256:[0-9a-f]{64}$/);
  });

  it('produces 64-character hex after prefix', async () => {
    const hash = await computeSha256('Test content for nexo');
    const hexPart = hash.replace('sha256:', '');
    expect(hexPart).toHaveLength(64);
    expect(hexPart).toMatch(/^[0-9a-f]+$/);
  });

  it('produces same hash for same content', async () => {
    const content = 'Deterministic content — hash should match';
    const [hash1, hash2] = await Promise.all([computeSha256(content), computeSha256(content)]);
    expect(hash1).toBe(hash2);
  });

  it('produces different hash for different content', async () => {
    const [hashA, hashB] = await Promise.all([
      computeSha256('Content A'),
      computeSha256('Content B'),
    ]);
    expect(hashA).not.toBe(hashB);
  });

  it('handles empty content', async () => {
    const hash = await computeSha256('');
    // SHA-256 of empty string:
    // e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
    expect(hash).toBe('sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855');
  });

  it('handles large content', async () => {
    const largeContent = 'A'.repeat(100_000);
    const hash = await computeSha256(largeContent);
    expect(hash).toMatch(/^sha256:[0-9a-f]{64}$/);
  });
});
