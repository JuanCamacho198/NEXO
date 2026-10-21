import { describe, expect, it } from 'vitest';
import {
  countPageTotal,
  pageNumberForSpine,
  spineForPageNumber,
  tocCoversSpine,
  NOT_A_PAGE,
} from '$lib/features/reader/viewer-epub/epubReachablePages';

/**
 * Measured on *Prisioneros de la geografía* (2026-10-21), straight from the
 * EPUB: 43 spine documents, 21 NCX navPoints. The numbering rule counts all 43.
 */
const PRISIONEROS_SPINE = 43;
const PRISIONEROS_TOC = 21;

/** Every book that is not broken: one document per TOC entry. */
const NORMAL_SPINE = 24;
const NORMAL_TOC = 24;

describe('epubReachablePages', () => {
  describe('pageNumberForSpine', () => {
    it('numbers a normal book exactly as before', () => {
      expect(pageNumberForSpine(0, NORMAL_SPINE)).toBe(1);
      expect(pageNumberForSpine(23, NORMAL_SPINE)).toBe(24);
    });

    it('counts the image-only documents of the real book', () => {
      // spine 7 is the "1 RUSIA" title page
      expect(pageNumberForSpine(7, PRISIONEROS_SPINE)).toBe(8);
      // spine 8 is the image spread with no prose — still page 9
      expect(pageNumberForSpine(8, PRISIONEROS_SPINE)).toBe(9);
      // spine 9 is the body of chapter 1 — page 10
      expect(pageNumberForSpine(9, PRISIONEROS_SPINE)).toBe(10);
    });

    it('numbers the cover as page 1 even though it has no text', () => {
      expect(pageNumberForSpine(0, PRISIONEROS_SPINE)).toBe(1);
    });

    it('gives the body a page adjacent to its title page', () => {
      expect(pageNumberForSpine(7, PRISIONEROS_SPINE)).toBe(
        pageNumberForSpine(9, PRISIONEROS_SPINE) - 2,
      );
    });

    it('returns NOT_A_PAGE past the spine and for a negative index', () => {
      expect(pageNumberForSpine(-1, PRISIONEROS_SPINE)).toBe(NOT_A_PAGE);
      expect(pageNumberForSpine(Number.NaN, PRISIONEROS_SPINE)).toBe(NOT_A_PAGE);
      expect(pageNumberForSpine(43, PRISIONEROS_SPINE)).toBe(NOT_A_PAGE);
    });

    it('does not reject an index while the spine length is unknown', () => {
      expect(pageNumberForSpine(0, 0)).toBe(1);
      expect(pageNumberForSpine(5, Number.NaN)).toBe(6);
    });
  });

  describe('countPageTotal', () => {
    it('counts every document of a normal book', () => {
      expect(countPageTotal(NORMAL_SPINE)).toBe(24);
    });

    it('counts all 43 documents of the real book, matching the footer', () => {
      expect(countPageTotal(PRISIONEROS_SPINE)).toBe(43);
      expect(countPageTotal(PRISIONEROS_SPINE)).not.toBe(PRISIONEROS_TOC);
    });

    it('returns 0 for no spine', () => {
      expect(countPageTotal(0)).toBe(0);
      expect(countPageTotal(Number.NaN)).toBe(0);
    });
  });

  describe('spineForPageNumber', () => {
    it('maps a normal book page straight to its index', () => {
      expect(spineForPageNumber(1, NORMAL_SPINE)).toBe(0);
      expect(spineForPageNumber(24, NORMAL_SPINE)).toBe(23);
    });

    it('maps the real book pages one-to-one onto the spine', () => {
      expect(spineForPageNumber(8, PRISIONEROS_SPINE)).toBe(7);
      expect(spineForPageNumber(9, PRISIONEROS_SPINE)).toBe(8);
      expect(spineForPageNumber(10, PRISIONEROS_SPINE)).toBe(9);
    });

    it('returns -1 past the last page', () => {
      expect(spineForPageNumber(44, PRISIONEROS_SPINE)).toBe(-1);
      expect(spineForPageNumber(0, PRISIONEROS_SPINE)).toBe(-1);
    });

    it('round-trips with pageNumberForSpine for every page', () => {
      for (let page = 1; page <= countPageTotal(PRISIONEROS_SPINE); page++) {
        const spine = spineForPageNumber(page, PRISIONEROS_SPINE);
        expect(spine).toBeGreaterThanOrEqual(0);
        expect(pageNumberForSpine(spine, PRISIONEROS_SPINE)).toBe(page);
      }
    });
  });

  describe('tocCoversSpine', () => {
    it('is true for a normal book, where the TOC can answer', () => {
      expect(tocCoversSpine(NORMAL_TOC, NORMAL_SPINE)).toBe(true);
    });

    it('is false for the real book, where only the spine can answer', () => {
      expect(tocCoversSpine(PRISIONEROS_TOC, PRISIONEROS_SPINE)).toBe(false);
    });

    it('is false when there is no outline at all', () => {
      expect(tocCoversSpine(0, NORMAL_SPINE)).toBe(false);
    });
  });
});
