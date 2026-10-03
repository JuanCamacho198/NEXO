import { invoke } from '$lib/shared/api/invokeWrapper';

export async function openDevTools(): Promise<void> {
  await invoke('openDevtools');
}
