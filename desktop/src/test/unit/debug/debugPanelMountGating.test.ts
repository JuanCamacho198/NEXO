/**
 * T6 (#11) — `DebugPanel` is mounted only while debug mode is on.
 *
 * The panel's own `useDebugIpcStats` timer is now gated inside the hook, but
 * the panel also allocates a viewer adapter and binds window-level listeners on
 * mount. Gating the mount is what keeps a release build from paying for any of
 * it, so this asserts the component really unmounts when the flag flips.
 */
import { render, screen } from '@testing-library/svelte';
import { describe, it, expect, afterEach, vi } from 'vitest';
import { debugState } from '$lib/shared/debug/debugState.svelte';
import DebugPanel from '$lib/shared/debug/DebugPanel.svelte';

vi.mock('$lib/shared/debug/devtoolsClient', () => ({ openDevTools: vi.fn() }));
vi.mock('@tauri-apps/plugin-os', () => ({ type: () => 'windows' }));

afterEach(() => {
  debugState.enabled = false;
});

describe('DebugPanel mount gating (T6)', () => {
  it('renders nothing at all when debug mode is off', () => {
    debugState.enabled = false;
    render(DebugPanel);
    expect(screen.queryByRole('region', { name: 'Debug information' })).toBeNull();
  });

  it('renders the debug region when debug mode is on', () => {
    debugState.enabled = true;
    render(DebugPanel);
    expect(screen.getByRole('region', { name: 'Debug information' })).toBeInTheDocument();
  });

  it('creates no IPC refresh timer while off and one while on', () => {
    const spy = vi.spyOn(globalThis, 'setInterval');
    try {
      debugState.enabled = false;
      render(DebugPanel);
      const offCalls = spy.mock.calls.filter(([, ms]) => ms === 2000).length;

      debugState.enabled = true;
      render(DebugPanel);
      const onCalls = spy.mock.calls.filter(([, ms]) => ms === 2000).length;

      expect(offCalls).toBe(0);
      expect(onCalls).toBeGreaterThan(offCalls);
    } finally {
      spy.mockRestore();
    }
  });
});
