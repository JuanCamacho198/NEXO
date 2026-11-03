/**
 * Active-user port.
 *
 * The desktop backend keeps a single "active user" slot that DB-layer code
 * reads to scope queries (see `ActiveUser` in `src-tauri/src/state.rs`). This
 * port is the frontend's only writer of that slot, and the session lifecycle
 * is deliberately the only caller: sign-in and session restore set it, sign-out
 * clears it. Nothing else should mutate it, or a stale id would outlive the
 * session that owns it.
 *
 * `setActiveUser` takes `string | null` because the Rust command accepts
 * `Option<String>`; passing `null` is a legitimate "no active user" write and
 * is not the same as `clearActiveUser`, which drops the slot entirely.
 */
export interface ActiveUserPort {
  /**
   * Marks `userId` as the active user for backend queries.
   *
   * Rejects when the backend rejects the write; callers on the session path
   * treat that as non-fatal and must not block sign-in on it.
   */
  setActiveUser(userId: string | null): Promise<void>;

  /** Drops the active user. Called on sign-out. */
  clearActiveUser(): Promise<void>;
}
