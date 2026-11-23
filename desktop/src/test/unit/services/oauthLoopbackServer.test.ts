import { describe, expect, it, vi } from 'vitest';

vi.mock('@fabianlars/tauri-plugin-oauth', () => ({ start: vi.fn() }));

import { LOOPBACK_PORTS, startLoopbackServer } from '$lib/shared/services/oauthLoopbackServer';

describe('startLoopbackServer', () => {
  // A fixed origin is what lets the browser remember "always open NEXO".
  it('asks for the preferred ports first', async () => {
    const startFn = vi.fn().mockResolvedValue(LOOPBACK_PORTS[0]);
    await expect(startLoopbackServer('<html/>', startFn)).resolves.toBe(LOOPBACK_PORTS[0]);
    expect(startFn).toHaveBeenCalledWith({ ports: [...LOOPBACK_PORTS], response: '<html/>' });
  });

  it('falls back to any free port when the preferred ones are taken', async () => {
    const startFn = vi
      .fn()
      .mockRejectedValueOnce(new Error('ports busy'))
      .mockResolvedValueOnce(51234);
    await expect(startLoopbackServer('<html/>', startFn)).resolves.toBe(51234);
    expect(startFn).toHaveBeenLastCalledWith({ response: '<html/>' });
  });
});
