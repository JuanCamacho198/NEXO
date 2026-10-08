import { describe, expect, it } from 'vitest';
import { resolveFitScalePercent, type FitViewport } from '$lib/features/reader/chrome/fitZoom.js';
import { clampZoomPercent, ZOOM_OPTIONS } from '$lib/features/reader/viewer-pdf/pdfNavigation.js';

const page = (containerWidth: number, containerHeight: number): FitViewport => ({
  containerWidth,
  containerHeight,
  pageWidth: 800,
  pageHeight: 1000,
});

describe('fitZoom', () => {
  it('fit-to-width divides the container width by the page width', () => {
    expect(resolveFitScalePercent('width', page(800, 600))).toBe(100);
    expect(resolveFitScalePercent('width', page(1200, 600))).toBe(150);
    expect(resolveFitScalePercent('width', page(1600, 600))).toBe(200);
  });

  it('fit-to-width ignores the container height and reaches the real edges', () => {
    expect(resolveFitScalePercent('width', page(1200, 10))).toBe(150);
    expect(resolveFitScalePercent('width', page(1200, 5000))).toBe(150);
  });

  it('fit-to-page takes the smaller of both ratios', () => {
    // width-bound: the full width fits, height has room to spare
    expect(resolveFitScalePercent('page', page(800, 2000))).toBe(100);
    // height-bound: the full height fits, width has room to spare
    expect(
      resolveFitScalePercent('page', {
        containerWidth: 2000,
        containerHeight: 1500,
        pageWidth: 800,
        pageHeight: 1000,
      }),
    ).toBe(150);
  });

  it('clamps extreme fits through the single zoom clamp instead of rejecting them', () => {
    expect(resolveFitScalePercent('width', page(4000, 600))).toBe(clampZoomPercent(500));
    expect(resolveFitScalePercent('page', page(80, 60))).toBe(
      clampZoomPercent(Math.min(80 / 800, 60 / 1000) * 100),
    );
  });

  it('falls back to 100% on degenerate viewports', () => {
    expect(resolveFitScalePercent('width', page(0, 600))).toBe(100);
    expect(resolveFitScalePercent('width', page(-800, 600))).toBe(100);
    expect(resolveFitScalePercent('width', page(NaN, 600))).toBe(100);
    expect(
      resolveFitScalePercent('page', {
        containerWidth: 800,
        containerHeight: 0,
        pageWidth: 800,
        pageHeight: 1000,
      }),
    ).toBe(100);
    expect(
      resolveFitScalePercent('width', {
        containerWidth: 800,
        containerHeight: 600,
        pageWidth: 0,
        pageHeight: 1000,
      }),
    ).toBe(100);
  });

  it('every dropdown option passes the clamp unchanged', () => {
    for (const opt of ZOOM_OPTIONS) {
      expect(clampZoomPercent(opt)).toBe(opt);
    }
  });
});
