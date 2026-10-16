import { fireEvent, render, screen } from '@testing-library/svelte';
import { tick } from 'svelte';
import { describe, expect, it, vi } from 'vitest';

const t = (key: string) => key;

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    setFullscreen: vi.fn(),
    minimize: vi.fn(),
    toggleMaximize: vi.fn(),
    close: vi.fn(),
    isMaximized: vi.fn().mockResolvedValue(false),
    onResized: vi.fn(),
  }),
}));

vi.mock('$lib/features/reader/viewer-pdf/PdfViewer.svelte', async () => {
  const mod = await import('../../mocks/MockPdfViewer.svelte');
  return { default: mod.default };
});

import ReaderWorkspace from '$lib/features/reader/chrome/ReaderWorkspace.svelte';

const makeBook = (overrides: Partial<{ format: string; filePath: string }> = {}) => ({
  id: 'book-1',
  title: 'Book',
  author: 'Author',
  filePath: 'C:/book.pdf',
  format: 'pdf',
  currentPage: 1,
  totalPages: 10,
  progressPercentage: 0,
  coverPath: null,
  minutesRead: 0,
  updatedAt: new Date().toISOString(),
  createdAt: new Date().toISOString(),
  ...overrides,
});

describe('ReaderWorkspace', () => {
  it('owns fullscreen at workspace root', async () => {
    const requestFullscreen = vi.fn(function (this: HTMLElement) {
      Object.defineProperty(document, 'fullscreenElement', {
        configurable: true,
        value: this,
      });
      document.dispatchEvent(new Event('fullscreenchange'));
      return Promise.resolve();
    });

    Object.defineProperty(document.documentElement, 'requestFullscreen', {
      configurable: true,
      value: requestFullscreen,
    });

    render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    await fireEvent.click(screen.getByTestId('mock-pdfviewer-toggle'));
    expect(requestFullscreen).toHaveBeenCalled();
  });
  it('renders a fluid page container instead of the fixed 800px card (U2.1)', () => {
    render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    expect(document.querySelector('.w-200')).toBeNull();
    const card = screen.getByTestId('mock-pdfviewer-toggle').closest('div.rounded-xl');
    expect(card).not.toBeNull();
    expect(card?.className).toContain('w-full');
    expect(card?.className).toContain('shadow-lg');
    expect(card?.className).toContain('bg-white');
  });
  it('renders selection toolbar using viewerSpace payload', async () => {
    render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    await fireEvent.click(screen.getByTestId('mock-pdfviewer-select'));
    const toolbar = document.querySelector('.selection-toolbar');
    expect(toolbar).toBeTruthy();
    expect(toolbar?.getAttribute('style')).toContain('left: 116px');
    expect(toolbar?.getAttribute('style')).toContain('top: 296px');
  });
  it('windowed root fits its parent box instead of the viewport (U1.1)', () => {
    const { container } = render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    const root = container.querySelector('section');
    expect(root?.className).toContain('h-full');
    expect(root?.className).toContain('min-h-0');
    expect(root?.className).not.toContain('h-screen');
  });
  it('fullscreen takeover root keeps fixed inset-0 and h-screen (U1.1)', async () => {
    const { container } = render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    await fireEvent.click(screen.getByLabelText('pdf.fullscreenEnter'));
    await tick();

    const root = container.querySelector('section');
    expect(root?.className).toContain('fixed inset-0');
    expect(root?.className).toContain('h-screen');
  });
  it('renders no phantom h-16 spacer for an overlay header (U1.3)', () => {
    const { container } = render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    expect(container.querySelector('div[aria-hidden="true"].h-16')).toBeNull();
  });
  it('applies the paper inset to the document wrapper only (U1.5)', () => {
    const { container } = render(ReaderWorkspace, {
      activeReadingBook: makeBook(),
      t,
      onBackToHome: () => undefined,
    });

    const card = screen.getByTestId('mock-pdfviewer-toggle').closest('div.rounded-xl');
    const wrapper = card?.parentElement;
    expect(wrapper?.className).toContain('px-10');
    expect(wrapper?.className).toContain('py-6');

    const header = container.querySelector('header');
    const footer = container.querySelector('footer');
    expect(header?.className).not.toContain('px-10');
    expect(header?.className).not.toContain('py-6');
    expect(footer?.className).not.toContain('px-10');
    expect(footer?.className).not.toContain('py-6');
  });
});
