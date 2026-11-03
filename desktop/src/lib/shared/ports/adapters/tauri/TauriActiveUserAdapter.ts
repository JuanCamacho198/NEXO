import type { ActiveUserPort } from '$lib/shared/ports/ActiveUserPort';
import * as tauriClient from '$lib/shared/api/tauriClient';

export class TauriActiveUserAdapter implements ActiveUserPort {
  setActiveUser(userId: string | null): Promise<void> {
    return tauriClient.setActiveUser(userId);
  }

  clearActiveUser(): Promise<void> {
    return tauriClient.clearActiveUser();
  }
}
