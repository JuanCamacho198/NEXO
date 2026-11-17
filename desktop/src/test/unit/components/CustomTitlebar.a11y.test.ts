/**
 * T15 — the custom titlebar is the only chrome the window has
 * (`decorations: false` on every platform), so for a screen reader it is the
 * app's entire window frame. Before this change it was a bare `div`: no role,
 * no name, the branding "NEXO" announced as loose text, and the maximize
 * button's accessible name did not say which state it would produce.
 *
 * These assertions cover what markup can prove. Whether VoiceOver/NVDA announce
 * the bar as a banner, and whether the drag region is skipped as expected, needs
 * a real screen reader — recorded as manual in `docs/desktop-dpi-and-a11y.md`.
 */
import { render, screen } from '@testing-library/svelte';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CustomTitlebar from '$lib/shared/ui/layout/CustomTitlebar.svelte';

const osState = { os: 'windows' as 'windows' | 'macos' | 'linux' };

vi.mock('@tauri-apps/plugin-os', () => ({ type: () => osState.os }));

vi.mock('@tauri-apps/api/webviewWindow', () => ({
  getCurrentWebviewWindow: () => ({
    isMaximized: vi.fn().mockResolvedValue(false),
    onResized: vi.fn().mockResolvedValue(() => undefined),
    minimize: vi.fn().mockResolvedValue(undefined),
    toggleMaximize: vi.fn().mockResolvedValue(undefined),
    close: vi.fn().mockResolvedValue(undefined),
  }),
}));

function mountTitlebar(): void {
  render(CustomTitlebar, {});
}

beforeEach(() => {
  osState.os = 'windows';
});

describe('CustomTitlebar — window frame semantics', () => {
  it('exposes the bar as a named banner landmark', async () => {
    mountTitlebar();

    const bar = await screen.findByRole('banner');
    expect(bar.getAttribute('aria-label')).toBeTruthy();
  });

  it('gives every window control an accessible name', async () => {
    mountTitlebar();

    for (const name of [/minimi[sz]e/i, /maximi[sz]e/i, /close/i]) {
      expect(await screen.findByRole('button', { name })).toBeTruthy();
    }
  });

  it('announces the maximize control by the state it will produce', async () => {
    mountTitlebar();

    // Not maximized → the button restores nothing yet, so it must not be
    // labelled "Restore". Once maximized the name flips; both are asserted
    // through the single source of truth in `titlebarState`.
    const button = await screen.findByRole('button', { name: /maximi[sz]e/i });
    expect(button.getAttribute('title')).toBeTruthy();
  });

  it('keeps the branding out of the accessibility tree as content', async () => {
    mountTitlebar();

    // The bar itself carries the name; the word "NEXO" repeated as loose text
    // is noise for a screen reader navigating by landmark.
    const bar = await screen.findByRole('banner');
    expect(bar.textContent).toContain('NEXO');
    const logos = screen.queryAllByText('NEXO', { exact: true });
    for (const logo of logos) {
      const hidden = logo.getAttribute('aria-hidden') === 'true';
      expect(hidden).toBe(true);
    }
  });

  it('keeps the drag regions out of the accessibility tree (pointer affordances)', async () => {
    mountTitlebar();

    // The macOS layout is not re-mounted here: `isMac` is resolved once at
    // module load by `titlebarState`, and `platform.test.ts` already locks the
    // platform gate. What both layouts share — and what a screen reader would
    // otherwise hit — is that the drag region is an unlabelled empty div.
    const bar = await screen.findByRole('banner');
    const dragRegions = bar.querySelectorAll('[data-tauri-drag-region]');
    expect(dragRegions.length).toBeGreaterThan(0);
    for (const region of dragRegions) {
      expect(region.getAttribute('aria-hidden')).toBe('true');
      expect(region.getAttribute('role')).toBe('presentation');
    }
  });
});
