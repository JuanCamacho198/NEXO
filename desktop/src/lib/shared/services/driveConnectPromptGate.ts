/**
 * Pure decision gate for the one-time "Connect Google Drive?" pre-prompt.
 *
 * Direct port of Android's `DriveConnectPromptGate` (no platform imports —
 * every input is passed in, so this is unit-testable without Tauri or a
 * webview). One intentional difference from Android: the `importSucceeded`
 * parameter is gone. On desktop the gate is evaluated only when a
 * Drive-gated action (cloud download, cold-backup export/import) is actually
 * invoked while unauthorized, so the trigger is implied by the call itself.
 *
 * Decision: show the prompt only when ALL hold — Drive is NOT yet authorized,
 * the user is signed in with Google, and this account has not previously
 * declined. Accepting persists nothing here: Drive becoming authorized gates
 * future prompts, and a later Settings disconnect MAY re-offer (the decline
 * marker is cleared on successful authorization).
 */

export interface DrivePromptInput {
  driveEnabled: boolean;
  providerIsGoogle: boolean;
  declinedForUser: string | null;
  currentUser: string | null;
}

/**
 * Whether the one-time connect prompt should appear for the current state.
 */
export function shouldShowDrivePrompt(input: DrivePromptInput): boolean {
  const { driveEnabled, providerIsGoogle, declinedForUser, currentUser } = input;
  if (driveEnabled || !providerIsGoogle) {
    return false;
  }
  if (currentUser === null || currentUser.trim().length === 0) {
    return false;
  }
  return declinedForUser !== currentUser;
}

/**
 * Compute the persisted decline marker for `currentUser`. Pure: returns the
 * value the caller must store so this account is not re-offered. Null when
 * there is no signed-in user to attribute the decline to.
 */
export function markDriveDeclined(currentUser: string | null): string | null {
  if (currentUser === null || currentUser.trim().length === 0) {
    return null;
  }
  return currentUser;
}

/**
 * localStorage key prefix for the per-user decline marker. The full key is
 * `nexo.drivePromptDeclined.<userId>`; localStorage is the marker home
 * (Q3 verified: it is the established durable UI-state store on this desktop
 * webview target — feedback queue, locale, storage auto-backup all persist
 * there — and it survives restart, satisfying the decline scenario).
 */
export const DRIVE_PROMPT_DECLINE_KEY_PREFIX = 'nexo.drivePromptDeclined.';

export function declineKeyForUser(userId: string): string {
  return `${DRIVE_PROMPT_DECLINE_KEY_PREFIX}${userId}`;
}
