/**
 * Anonymous-session detection (DA-3).
 *
 * The boot fallback signs in anonymously so RLS has an auth context when no
 * real session can be restored. Supabase Auth marks anonymous users with the
 * `is_anonymous` claim on the User payload; this predicate is the single
 * source of truth for the marker carried by `authState`, so a session restored
 * on a later launch is recognized as anonymous too — not just the in-flight
 * sign-in result.
 *
 * Kept dependency-free (type-only Supabase import) so it can be exercised
 * without loading the Tauri OAuth plugins.
 */
import type { Session } from '@supabase/supabase-js';

export function isAnonymousSession(session: Pick<Session, 'user'>): boolean {
  return session.user.is_anonymous === true;
}
