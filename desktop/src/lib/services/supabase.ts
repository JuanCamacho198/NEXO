/**
 * Supabase client factory with:
 * - Anonymous client (legacy, unauthenticated)
 * - Session-aware client using Tauri fs-based storage adapter
 * - AuthStorageAdapter for persisting Supabase session to disk
 */

import { createClient, type Session, type SupabaseClient } from '@supabase/supabase-js';
import {
  BaseDirectory,
  writeTextFile,
  readTextFile,
  exists,
  rename,
  remove,
} from '@tauri-apps/plugin-fs';
import { authState } from '$lib/shared/stores/AuthState.svelte';
import { logger } from '$lib/shared/logger/Logger';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';
import { redactLogLine } from '$lib/shared/protocol/DriveCatalogContract';

const SESSION_FILE = 'supabase-session.json';
const SESSION_TMP_FILE = 'supabase-session.json.tmp';
const BASE_DIR = BaseDirectory.AppData;

// ─── Session Storage Adapter ──────────────────────────────────────
// supabase-js normally uses localStorage. Tauri webview's localStorage
// is ephemeral (cleared on app restart). This adapter persists the
// session to a JSON file in appDataDir so it survives restarts.
//
// Writes are atomic (tmp file + rename, mirroring authPersistence.ts
// savePersistedAuth) so a crash mid-write can never leave a truncated
// main file: after any crash the main file is either the old valid JSON
// or the new valid JSON, and the orphaned tmp is cleaned on the next
// write (DA-4.1). Reads never throw and never touch the live in-memory
// session mirror (liveSessionCache) — a corrupt file degrades to "no
// persisted session" with a typed event, not a crash or session wipe
// (DA-4.2).
export const tauriStorageAdapter = {
  getItem: async (key: string): Promise<string | null> => {
    try {
      const fileExists = await exists(SESSION_FILE, { baseDir: BASE_DIR });
      if (!fileExists) return null;
      const raw = await readTextFile(SESSION_FILE, { baseDir: BASE_DIR });
      const data = JSON.parse(raw) as Record<string, string>;
      return data[key] ?? null;
    } catch (error) {
      // Corrupt or unreadable session file: return null (never throw) and
      // surface a typed event. The live in-memory session is separate
      // (liveSessionCache) and is NOT wiped by a read failure (DA-4.2).
      logger.warn(
        createErrorEvent({
          severity: 'low',
          category: 'runtime',
          code: 'SUPABASE_AUTH_CACHE_READ_FAILED',
          message: 'Failed to read Supabase session cache; treating as no persisted session.',
          context: { reason: error instanceof Error ? error.message : String(error) },
          source: 'app_shell',
          recoverable: true,
        }),
      );
      return null;
    }
  },
  setItem: async (key: string, value: string): Promise<void> => {
    try {
      await removeStaleTmp();
      let data: Record<string, string> = {};
      const fileExists = await exists(SESSION_FILE, { baseDir: BASE_DIR });
      if (fileExists) {
        try {
          const raw = await readTextFile(SESSION_FILE, { baseDir: BASE_DIR });
          data = JSON.parse(raw) as Record<string, string>;
        } catch {
          // corrupted main file, start fresh
        }
      }
      data[key] = value;
      // Atomic write: write to tmp, then rename over the main file. A crash
      // between these steps leaves the old main file intact (DA-4.1).
      await writeTextFile(SESSION_TMP_FILE, JSON.stringify(data, null, 0), {
        baseDir: BASE_DIR,
      });
      await rename(SESSION_TMP_FILE, SESSION_FILE, {
        oldPathBaseDir: BASE_DIR,
        newPathBaseDir: BASE_DIR,
      });
    } catch (error) {
      // Best-effort persist, but never silent: a failed session write means the
      // session may not survive a restart, so it is reported as a typed event
      // (redacted — the reason can carry a path/token).
      logger.warn(
        createErrorEvent({
          severity: 'medium',
          category: 'runtime',
          code: 'SUPABASE_SESSION_PERSIST_FAILED',
          message:
            'Failed to persist the Supabase session cache; the session may not survive a restart.',
          context: {
            reason: redactLogLine(error instanceof Error ? error.message : String(error)),
          },
          source: 'app_shell',
          recoverable: true,
        }),
      );
    }
  },
  removeItem: async (key: string): Promise<void> => {
    try {
      await removeStaleTmp();
      const fileExists = await exists(SESSION_FILE, { baseDir: BASE_DIR });
      if (!fileExists) return;
      const raw = await readTextFile(SESSION_FILE, { baseDir: BASE_DIR });
      const data = JSON.parse(raw) as Record<string, string>;
      delete data[key];
      // Atomic rewrite, same crash-safety as setItem (DA-4.1).
      await writeTextFile(SESSION_TMP_FILE, JSON.stringify(data, null, 0), {
        baseDir: BASE_DIR,
      });
      await rename(SESSION_TMP_FILE, SESSION_FILE, {
        oldPathBaseDir: BASE_DIR,
        newPathBaseDir: BASE_DIR,
      });
    } catch {
      // best-effort
    }
  },
};

/**
 * Remove a stale `.tmp` session file left behind by a crashed write. The main
 * file is always the source of truth after a crash (old or new valid JSON), so
 * any orphaned tmp is garbage. Runs before each write (DA-4.1). Best-effort —
 * a failure here does not block the write itself.
 */
async function removeStaleTmp(): Promise<void> {
  try {
    if (await exists(SESSION_TMP_FILE, { baseDir: BASE_DIR })) {
      await remove(SESSION_TMP_FILE, { baseDir: BASE_DIR });
    }
  } catch {
    // best-effort
  }
}

// ─── Client instances ─────────────────────────────────────────────

let anonClient: SupabaseClient | null = null;
let sessionClient: SupabaseClient | null = null;

function getSupabaseUrl(): string {
  const url = import.meta.env.VITE_SUPABASE_URL as string;
  if (!url) throw new Error('VITE_SUPABASE_URL is not set');
  return url;
}

function getSupabaseAnonKey(): string {
  const key = import.meta.env.VITE_SUPABASE_ANON_KEY as string;
  if (!key) throw new Error('VITE_SUPABASE_ANON_KEY is not set');
  return key;
}

/**
 * Get the anonymous Supabase client (legacy, no auth session).
 * Used for unauthenticated reads where RLS permits anon access.
 */
export function getSupabaseClient(): SupabaseClient {
  if (!anonClient) {
    const url = getSupabaseUrl();
    const anonKey = getSupabaseAnonKey();
    anonClient = createClient(url, anonKey, {
      auth: {
        persistSession: false,
        autoRefreshToken: false,
        detectSessionInUrl: false,
      },
    });
  }
  return anonClient;
}

/**
 * Get the session-authenticated Supabase client.
 * Uses Tauri fs-based storage adapter for session persistence.
 * Call this after the user signs in (or when anon session is active).
 */
export function getSessionClient(): SupabaseClient {
  if (!sessionClient) {
    const url = getSupabaseUrl();
    const anonKey = getSupabaseAnonKey();
    sessionClient = createClient(url, anonKey, {
      auth: {
        storage: tauriStorageAdapter,
        autoRefreshToken: true,
        detectSessionInUrl: false,
        // PKCE is required for the OAuth callback to receive Google provider
        // tokens. The implicit flow (the default) drops provider_token, which
        // broke Drive sync after restart: login worked in-memory but the
        // restored session had no Drive token (DRIVE_TOKEN_MISSING).
        flowType: 'pkce',
      },
    });
  }
  return sessionClient;
}

/**
 * Reset the session client (used on sign-out to force fresh state).
 */
export function resetSessionClient(): void {
  sessionClient = null;
}

// ─── Live-session cache (auth gate) ────────────────────────────────
// Module-level mirror of the current supabase-js session, maintained by
// AppState's single `onAuthStateChange` subscription. This cache — NOT
// `authState` alone — is the sync-gate authority (D1): a sync path may
// issue PostgREST requests only while `hasLiveSession()` is true. Async
// sync paths additionally re-verify via `getSession()` once to cover
// silent session drops that emit no event (DA-1 "stale without event").
let liveSessionCache: Session | null = null;

/**
 * Record the current live session (or null) from an auth lifecycle event.
 */
export function setLiveSession(session: Session | null): void {
  liveSessionCache = session;
}

/**
 * Drop the live-session mirror (session loss — SIGNED_OUT, sign-out).
 */
export function clearLiveSession(): void {
  liveSessionCache = null;
}

/**
 * Read the cached live session. Async paths use this to decide whether an
 * extra `getSession()` re-check is needed before gating (D1).
 */
export function getLiveSession(): Session | null {
  return liveSessionCache;
}

/**
 * Hot-path auth gate: true while the live client holds a session whose
 * user matches the current `authState` user. Anonymous sessions pass only
 * when they own the current authState user id — an anon session can never
 * gate work under a real user id (D3, DA-1, R4).
 */
export function hasLiveSession(): boolean {
  return liveSessionCache !== null && liveSessionCache.user.id === authState.userId;
}

/**
 * Async gate for slow sync paths (flush, syncMetadata, syncState): hot
 * `hasLiveSession()` first, then ONE `getSession()` re-check to cover silent
 * session drops that emit no auth event (D1, DA-1 "stale without event").
 * Never throws — a failed re-check is a closed gate (SR-1: no request fires,
 * no markFailed, no reschedule).
 */
export async function recheckLiveSession(): Promise<boolean> {
  if (!hasLiveSession()) return false;
  try {
    const { data } = await getSessionClient().auth.getSession();
    return data.session !== null && data.session.user.id === authState.userId;
  } catch {
    return false;
  }
}
