/**
 * Independent durable Drive grant store (login-drive-separation, foundation).
 *
 * Holds the on-demand Drive OAuth grant in `appDataDir/drive.json`, separate
 * from `auth.json`, so identity sign-out, session refresh, or session clear
 * never implicitly wipe or move Drive tokens. Writes are atomic (tmp file +
 * rename) and DPAPI-sealed (see `secretVault.ts`); legacy plaintext files are
 * re-sealed on first read with no backup kept.
 *
 * `migrateLoginGrantOnce()` is the one-shot seeder for pre-separation
 * installs: it copies the login-coupled `provider_refresh_token` out of
 * `auth.json` into `drive.json` the first time it runs, then never overwrites
 * an existing grant.
 */

import { BaseDirectory, exists, remove } from '@tauri-apps/plugin-fs';
import { logger } from '$lib/shared/logger/Logger';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';
import { DRIVE_SCOPE } from '$lib/shared/protocol/DriveCatalogContract';
import { loadPersistedAuth } from '$lib/shared/stores/authPersistence';
import { readSealedFile, writeSealedFile } from '$lib/shared/services/secretVault';

const DRIVE_FILE = 'drive.json';
const DRIVE_TMP_FILE = 'drive.json.tmp';
const BASE_DIR = BaseDirectory.AppData;

export interface DriveGrant {
  refreshToken: string;
  scope: string;
  obtainedAt: number;
}

/**
 * Load the stored Drive grant. Returns `null` when the file does not exist,
 * contains malformed JSON, or fails validation — never throws, so a corrupt
 * grant degrades to a re-connect prompt instead of a crash.
 */
export async function loadDriveGrant(): Promise<DriveGrant | null> {
  try {
    const opened = await readSealedFile(
      DRIVE_FILE,
      DRIVE_TMP_FILE,
      'DRIVE_GRANT_MIGRATED_TO_SEALED',
    );
    if (opened === null) {
      return null;
    }
    return validateDriveGrant(JSON.parse(opened.plaintext) as unknown);
  } catch (error) {
    logger.warn(
      createErrorEvent({
        severity: 'low',
        category: 'runtime',
        code: 'DRIVE_GRANT_READ_FAILED',
        message: 'Failed to read Drive grant; treating as not connected.',
        context: { reason: error instanceof Error ? error.message : String(error) },
        source: 'sync',
        recoverable: true,
      }),
    );
    return null;
  }
}

/**
 * Persist the Drive grant atomically, DPAPI-sealed (tmp file + rename).
 */
export async function saveDriveGrant(grant: DriveGrant): Promise<void> {
  const payload = JSON.stringify(grant);
  await writeSealedFile(DRIVE_FILE, DRIVE_TMP_FILE, payload);
}

/**
 * Remove the stored Drive grant. Best-effort: never throws, so disconnect
 * always converges to the unauthorized state even when the disk fails.
 */
export async function clearDriveGrant(): Promise<void> {
  try {
    const fileExists = await exists(DRIVE_FILE, { baseDir: BASE_DIR });
    if (!fileExists) {
      const tmpExists = await exists(DRIVE_TMP_FILE, { baseDir: BASE_DIR });
      if (tmpExists) {
        await remove(DRIVE_TMP_FILE, { baseDir: BASE_DIR });
      }
      return;
    }
    await remove(DRIVE_FILE, { baseDir: BASE_DIR });
  } catch (error) {
    logger.warn(
      createErrorEvent({
        severity: 'low',
        category: 'runtime',
        code: 'DRIVE_GRANT_CLEAR_FAILED',
        message: 'Failed to clear Drive grant.',
        context: { reason: error instanceof Error ? error.message : String(error) },
        source: 'sync',
        recoverable: true,
      }),
    );
  }
}

/**
 * One-shot migration from the pre-separation login-coupled grant.
 *
 * - Returns `'skipped'` when `drive.json` already holds a grant (never
 *   overwrites) or when `auth.json` carries no usable login grant.
 * - Returns `'seeded'` after copying the login `provider_refresh_token` into
 *   `drive.json`; the next call then observes the seeded grant and skips.
 */
export async function migrateLoginGrantOnce(): Promise<'seeded' | 'skipped'> {
  const existing = await loadDriveGrant();
  if (existing !== null) {
    return 'skipped';
  }

  const loginToken = await loadLoginGrantFromAuthJson();
  if (loginToken === null) {
    return 'skipped';
  }

  await saveDriveGrant({
    refreshToken: loginToken,
    scope: DRIVE_SCOPE,
    obtainedAt: Date.now(),
  });
  return 'seeded';
}

/**
 * Read the pre-separation login-coupled `provider_refresh_token` via the
 * sealed auth cache (login-drive-separation work unit 4: the seeder owns
 * migration — `authPersistence.ts` no longer exposes Drive-token helpers).
 * Handles sealed and legacy-plaintext `auth.json` alike. Returns `null`
 * when missing, corrupt, or malformed — never throws.
 */
async function loadLoginGrantFromAuthJson(): Promise<string | null> {
  try {
    const persisted = await loadPersistedAuth();
    if (persisted === null || persisted.kind !== 'supabase') return null;
    const value = persisted.session.provider_refresh_token;
    return typeof value === 'string' && value.length > 0 ? value : null;
  } catch {
    return null;
  }
}

// ─── Internal validation ───

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function validateDriveGrant(value: unknown): DriveGrant | null {
  if (!isObject(value)) {
    return null;
  }
  if (typeof value.refreshToken !== 'string' || value.refreshToken.length === 0) {
    return null;
  }
  if (typeof value.scope !== 'string' || value.scope.length === 0) {
    return null;
  }
  if (typeof value.obtainedAt !== 'number' || !Number.isFinite(value.obtainedAt)) {
    return null;
  }
  return {
    refreshToken: value.refreshToken,
    scope: value.scope,
    obtainedAt: value.obtainedAt,
  };
}
