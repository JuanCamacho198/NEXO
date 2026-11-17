import { describe, expect, it } from 'vitest';
import { isCustomTitlebarPlatform, usesMacTitlebarLayout } from '$lib/shared/utils/platform';

describe('isCustomTitlebarPlatform', () => {
  it.each([
    ['windows', true],
    ['Windows', false],
    ['macos', true],
    ['linux', true],
    ['', false],
    [undefined, false],
  ] as Array<[string | undefined, boolean]>)('returns %s for osType=%s', (osType, expected) => {
    expect(isCustomTitlebarPlatform(osType)).toBe(expected);
  });

  it('renders the custom titlebar only where the window has no native decorations', () => {
    // `tauri.conf.json` sets `decorations: false` as a static value, so the same
    // chrome decision applies to every platform the desktop bundle targets. A
    // platform that does not render the custom bar therefore has no window
    // controls at all: no close, no minimise, no maximise. This guard is the
    // only thing standing between that config and a window a mouse cannot close.
    expect(isCustomTitlebarPlatform('windows')).toBe(true);
    expect(isCustomTitlebarPlatform('macos')).toBe(true);
    expect(isCustomTitlebarPlatform('linux')).toBe(true);
  });
});

describe('usesMacTitlebarLayout', () => {
  it.each([
    ['macos', true],
    ['windows', false],
    ['linux', false],
    ['macOS', false],
    ['', false],
    [undefined, false],
  ] as Array<[string | undefined, boolean]>)('returns %s for osType=%s', (osType, expected) => {
    expect(usesMacTitlebarLayout(osType)).toBe(expected);
  });
});
