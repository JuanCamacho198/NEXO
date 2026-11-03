import type { ActiveUserPort } from '$lib/shared/ports/ActiveUserPort';

/**
 * In-memory active-user double for tests.
 *
 * `setActiveUser(null)` and `clearActiveUser()` both leave the slot empty, but
 * `calls` keeps them apart: the session lifecycle means different things by
 * each, and a test asserting "sign-out cleared" must be able to see which one
 * ran.
 */
export class MockActiveUserAdapter implements ActiveUserPort {
  #activeUserId: string | null = null;
  #calls: string[] = [];

  /** The last id written, or `null` when the slot is empty — for assertions. */
  get activeUserId(): string | null {
    return this.#activeUserId;
  }

  /** Every call received, oldest first — for assertions on wiring. */
  get calls(): ReadonlyArray<string> {
    return this.#calls;
  }

  async setActiveUser(userId: string | null): Promise<void> {
    this.#calls.push(`set:${userId === null ? 'null' : userId}`);
    this.#activeUserId = userId;
  }

  async clearActiveUser(): Promise<void> {
    this.#calls.push('clear');
    this.#activeUserId = null;
  }
}
