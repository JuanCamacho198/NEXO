import { describe, it, expect, vi, beforeEach } from 'vitest';
import {
  createSettingsRouter,
  SETTINGS_TABS,
} from '$lib/features/settings/useSettingsRouter.svelte';

describe('useSettingsRouter', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('defaults to cuenta when no initialTab', () => {
    const router = createSettingsRouter();
    expect(router.activeTab).toBe('cuenta');
  });

  it('respects initialTab', () => {
    const router = createSettingsRouter({ initialTab: 'atajos' });
    expect(router.activeTab).toBe('atajos');
  });

  it('handleTabChange sets activeTab', async () => {
    const router = createSettingsRouter();
    await router.handleTabChange('datos');
    expect(router.activeTab).toBe('datos');
  });

  it('SETTINGS_TABS has 6 entries', () => {
    expect(SETTINGS_TABS).toHaveLength(6);
    expect(SETTINGS_TABS).toEqual([
      'cuenta',
      'datos',
      'almacenamiento',
      'sincronizacion',
      'atajos',
      'acerca',
    ]);
  });

  function mockFocus() {
    const focus = vi.fn();
    const spy = vi
      .spyOn(document, 'getElementById')
      .mockReturnValue({ focus } as unknown as HTMLElement);
    return { focus, spy };
  }

  it('ArrowRight moves to next tab and focuses', () => {
    const router = createSettingsRouter({ initialTab: 'cuenta' });
    const { focus, spy } = mockFocus();
    const e = { key: 'ArrowRight', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(e.preventDefault).toHaveBeenCalled();
    expect(router.activeTab).toBe('datos');
    expect(spy).toHaveBeenCalledWith('tab-datos');
    expect(focus).toHaveBeenCalled();
  });

  it('ArrowLeft wraps to last', () => {
    const router = createSettingsRouter({ initialTab: 'cuenta' });
    const { focus } = mockFocus();
    const e = { key: 'ArrowLeft', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(router.activeTab).toBe('acerca');
    expect(focus).toHaveBeenCalled();
  });

  it('Home goes to first', () => {
    const router = createSettingsRouter({ initialTab: 'datos' });
    const { focus } = mockFocus();
    const e = { key: 'Home', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(router.activeTab).toBe('cuenta');
    expect(focus).toHaveBeenCalled();
  });

  it('End goes to last', () => {
    const router = createSettingsRouter({ initialTab: 'cuenta' });
    const { focus } = mockFocus();
    const e = { key: 'End', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(router.activeTab).toBe('acerca');
    expect(focus).toHaveBeenCalled();
  });

  it('ArrowRight cycles from last to first', () => {
    const router = createSettingsRouter({ initialTab: 'acerca' });
    mockFocus();
    const e = { key: 'ArrowRight', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(router.activeTab).toBe('cuenta');
  });

  it('ignores unknown keys', () => {
    const router = createSettingsRouter({ initialTab: 'cuenta' });
    const e = { key: 'Enter', preventDefault: vi.fn() } as unknown as KeyboardEvent;
    router.handleTabKeydown(e);
    expect(router.activeTab).toBe('cuenta');
    expect(e.preventDefault).not.toHaveBeenCalled();
  });
});
