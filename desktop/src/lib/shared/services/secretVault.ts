/**
 * DPAPI-sealed file envelope (0.3.5 secrets encryption).
 *
 * Files holding secrets (`auth.json`, `drive.json`, `supabase-session.json`)
 * are stored as `{ v: 1, alg: 'dpapi-current-user', data: <hex> }` where `data`
 * is the Windows DPAPI (CurrentUser scope) ciphertext of the JSON payload.
 *
 * Threat model: stolen laptop / disk removed. DPAPI + Keystore satisfy this.
 * Malware running as the user is explicitly OUT of scope — DPAPI decrypts for
 * any process running as the user, so no attempt is made to defend against it.
 *
 * Migration: a file that parses as anything other than this envelope is
 * treated as legacy plaintext — it is read normally, then re-sealed on disk
 * atomically (write `.tmp`, then rename), keeping NO plaintext backup. A
 * redacted migration event is logged; secret bytes are never logged.
 */

import { invoke } from '@tauri-apps/api/core';
import { BaseDirectory, exists, readTextFile, rename, writeTextFile } from '@tauri-apps/plugin-fs';
import { logger } from '$lib/shared/logger/Logger';
import { createErrorEvent } from '$lib/shared/events/ErrorEvent';

const ENVELOPE_VERSION = 1;
const ENVELOPE_ALG = 'dpapi-current-user';
const BASE_DIR = BaseDirectory.AppData;

export interface SecretEnvelope {
  v: number;
  alg: string;
  data: string;
}

export interface OpenedPayload {
  /** Decrypted (or legacy plaintext) file content. */
  plaintext: string;
  /** True when the file was legacy plaintext and still needs re-sealing. */
  wasLegacy: boolean;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/**
 * True when `value` is a DPAPI sealed envelope (not legacy plaintext).
 */
export function isSecretEnvelope(value: unknown): value is SecretEnvelope {
  return (
    isRecord(value) &&
    value.v === ENVELOPE_VERSION &&
    value.alg === ENVELOPE_ALG &&
    typeof value.data === 'string'
  );
}

/**
 * DPAPI-protect `plaintext` and wrap it in the on-disk envelope.
 */
export async function sealPayload(plaintext: string): Promise<string> {
  const ciphertext = await invoke<string>('protectSecret', { plaintext });
  return JSON.stringify({ v: ENVELOPE_VERSION, alg: ENVELOPE_ALG, data: ciphertext });
}

/**
 * Open a raw file body: unprotect sealed envelopes, pass legacy plaintext
 * through with `wasLegacy: true`. Returns `null` when the body is unusable
 * (corrupt JSON, undecryptable ciphertext) — never throws, never leaks
 * secret bytes.
 */
export async function openPayload(raw: string): Promise<OpenedPayload | null> {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw) as unknown;
  } catch {
    return null;
  }
  if (isSecretEnvelope(parsed)) {
    try {
      const plaintext = await invoke<string>('unprotectSecret', { ciphertext: parsed.data });
      return { plaintext, wasLegacy: false };
    } catch {
      return null;
    }
  }
  return { plaintext: raw, wasLegacy: true };
}

/**
 * Atomically write `plaintext` sealed to `file` (via `tmpFile` + rename).
 */
export async function writeSealedFile(
  file: string,
  tmpFile: string,
  plaintext: string,
): Promise<void> {
  const sealed = await sealPayload(plaintext);
  await writeTextFile(tmpFile, sealed, { baseDir: BASE_DIR });
  await rename(tmpFile, file, { oldPathBaseDir: BASE_DIR, newPathBaseDir: BASE_DIR });
}

/**
 * Read a secret file, transparently handling sealed and legacy bodies.
 * A legacy body is re-sealed on disk (no backup kept) and a redacted
 * migration event is logged. Returns `null` when missing or unusable.
 */
export async function readSealedFile(
  file: string,
  tmpFile: string,
  eventCode: string,
): Promise<OpenedPayload | null> {
  try {
    if (!(await exists(file, { baseDir: BASE_DIR }))) {
      return null;
    }
    const raw = await readTextFile(file, { baseDir: BASE_DIR });
    const opened = await openPayload(raw);
    if (opened === null) {
      return null;
    }
    if (opened.wasLegacy) {
      try {
        await writeSealedFile(file, tmpFile, opened.plaintext);
        logger.warn(
          createErrorEvent({
            severity: 'low',
            category: 'runtime',
            code: eventCode,
            message: 'Migrated a legacy plaintext secret file to DPAPI-sealed storage.',
            context: { file },
            source: 'app_shell',
            recoverable: true,
          }),
        );
      } catch (error) {
        logger.warn(
          createErrorEvent({
            severity: 'medium',
            category: 'runtime',
            code: eventCode,
            message: 'Failed to re-seal a legacy secret file; continuing with in-memory value.',
            context: {
              file,
              reason: error instanceof Error ? error.message : String(error),
            },
            source: 'app_shell',
            recoverable: true,
          }),
        );
      }
    }
    return opened;
  } catch {
    return null;
  }
}
