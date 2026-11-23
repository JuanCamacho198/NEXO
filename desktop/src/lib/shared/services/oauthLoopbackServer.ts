import { start } from '@fabianlars/tauri-plugin-oauth';

/**
 * Preferred loopback ports for the OAuth redirect. A browser remembers "always
 * allow this site to open NEXO" per origin, port included; a random port made
 * every sign-in a new origin, so the choice never stuck. The first free port
 * of this short list keeps the origin stable (`http://127.0.0.1:47321`).
 */
export const LOOPBACK_PORTS: readonly number[] = [47321, 47322, 47323];

type StartFn = (config: { ports?: number[]; response?: string }) => Promise<number>;

/**
 * Starts the loopback server on a preferred port, or on any free port when all
 * of them are taken, so a busy port never blocks a sign-in.
 */
export async function startLoopbackServer(
  response: string,
  startFn: StartFn = start,
): Promise<number> {
  try {
    return await startFn({ ports: [...LOOPBACK_PORTS], response });
  } catch {
    return await startFn({ response });
  }
}
