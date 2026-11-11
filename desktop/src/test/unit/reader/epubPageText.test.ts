/**
 * The chapter plain-text extraction behind the translation language fallback.
 *
 * The consumer (the language detector) needs a few hundred words of the page,
 * and the page must be the chapter the reader is actually on. These lock down
 * that extraction: prose only, whitespace collapsed, big chapters bounded, and a
 * text-free document honestly yielding nothing.
 */
import { describe, expect, it } from 'vitest';
import {
  extractPageText,
  PAGE_TEXT_MAX_CHARS,
} from '$lib/features/reader/viewer-epub/epubPageText';

describe('extractPageText', () => {
  it('collects body prose and collapses whitespace', () => {
    const html = `<!DOCTYPE html><html><head><title>t</title>
      <style>p { color: red }</style></head><body>
      <p>Hay   un\n   límite</p>
      <p>a lo que   podemos soportar.</p>
      </body></html>`;
    const text = extractPageText(html);
    expect(text).toContain('Hay un límite');
    expect(text).toContain('a lo que podemos soportar.');
    // No runs of whitespace survive.
    expect(text).not.toMatch(/\s{2,}/);
  });

  it('excludes script, style and head text', () => {
    const html = `<html><head><title>bookmark</title>
      <style>.hero { content: 'from-css' }</style></head><body>
      <p>chapter body</p>
      <script>var injected = 'from-script';</script>
      </body></html>`;
    const text = extractPageText(html);
    expect(text).toBe('chapter body');
    expect(text).not.toContain('from-css');
    expect(text).not.toContain('from-script');
    expect(text).not.toContain('bookmark');
  });

  it('returns empty for an image-only document rather than guessing', () => {
    const html = `<html><body><div><img src="plate.jpg" alt="map"></div></body></html>`;
    expect(extractPageText(html)).toBe('');
  });

  it('returns empty for empty and whitespace-only input', () => {
    expect(extractPageText('')).toBe('');
    expect(extractPageText('   \n  ')).toBe('');
  });

  it('caps a chapter far larger than the detector needs', () => {
    const word = 'palabra ';
    const longSentence = word.repeat(Math.ceil(PAGE_TEXT_MAX_CHARS / word.length) + 2000);
    const html = `<html><body><p>${longSentence}</p></body></html>`;
    const text = extractPageText(html);
    expect(text.length).toBeLessThanOrEqual(PAGE_TEXT_MAX_CHARS);
    expect(text.length).toBeGreaterThan(1000);
  });

  it('honours a caller-supplied cap', () => {
    const html = '<html><body><p>' + 'uno dos tres cuatro cinco '.repeat(20) + '</p></body></html>';
    expect(extractPageText(html, 40).length).toBe(40);
  });
});
