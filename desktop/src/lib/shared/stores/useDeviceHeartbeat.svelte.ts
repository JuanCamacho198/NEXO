import { getSessionClient } from '$lib/services/supabase';
import { updateHeartbeat } from '$lib/services/devices';

/**
 * Heartbeat composable for device presence.
 * Starts a 120s interval that calls `updateHeartbeat` with the session client.
 * `stop` clears the interval, `start` restarts it for a new device id.
 */
export function createDeviceHeartbeat(options?: {
  getClient?: () => ReturnType<typeof getSessionClient>;
  intervalMs?: number;
}): {
  start(id: string): void;
  stop(): void;
  destroy(): void;
} {
  const getClient = options?.getClient ?? getSessionClient;
  const intervalMs = options?.intervalMs ?? 120_000;
  let timer: ReturnType<typeof setInterval> | null = null;

  function stop(): void {
    if (timer) {
      clearInterval(timer);
      timer = null;
    }
  }

  function start(id: string): void {
    stop();
    timer = setInterval(async () => {
      try {
        await updateHeartbeat(getClient(), id);
      } catch {
        /* silent */
      }
    }, intervalMs);
  }

  function destroy(): void {
    stop();
  }

  return { start, stop, destroy };
}

export type DeviceHeartbeat = ReturnType<typeof createDeviceHeartbeat>;
