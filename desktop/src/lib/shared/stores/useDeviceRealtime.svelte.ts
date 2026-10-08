import { getSessionClient } from '$lib/services/supabase';
import { rowToViewModel, type DeviceRow, type DeviceViewModel } from '$lib/services/devices';
import type { RealtimeChannel, RealtimePostgresChangesPayload } from '@supabase/supabase-js';

export function createDeviceRealtime(opts: {
  getHardwareId: () => string;
  getDevices: () => DeviceViewModel[];
  setDevices: (v: DeviceViewModel[]) => void;
}): {
  subscribe(userId: string): void;
  unsubscribe(): void;
  destroy(): void;
  readonly isSubscribed: boolean;
  readonly channel: RealtimeChannel | null;
} {
  let channel: RealtimeChannel | null = null;
  let currentUserId: string | null = null;
  let isSubscribed = $state(false);
  let unsub: (() => void) | null = null;
  function teardown(): void {
    if (channel) {
      try {
        channel.unsubscribe();
      } catch {}
      try {
        getSessionClient().removeChannel(channel);
      } catch {}
      channel = null;
    }
    currentUserId = null;
    isSubscribed = false;
    unsub = null;
  }
  function subscribe(userId: string): void {
    const client = getSessionClient() as unknown as {
      getChannels?: () => RealtimeChannel[];
      getChannel?: (n: string) => RealtimeChannel | undefined;
      channel: (n: string) => RealtimeChannel;
      removeChannel: (c: RealtimeChannel) => void;
    };
    const name = `devices:${userId}`;
    if (
      currentUserId === userId &&
      channel &&
      (isSubscribed || (channel as unknown as { state?: string }).state === 'subscribed')
    )
      return;
    if (currentUserId !== null && currentUserId !== userId) teardown();
    let existing: RealtimeChannel | null = null;
    if (typeof client.getChannel === 'function') {
      try {
        existing = (client.getChannel(name) as RealtimeChannel | undefined) ?? null;
      } catch {
        existing = null;
      }
    }
    if (!existing && typeof client.getChannels === 'function') {
      try {
        const all = client.getChannels();
        existing =
          (all.find((c) =>
            (
              (c as unknown as { topic?: string }).topic ??
              (c as unknown as { channelName?: string }).channelName ??
              ''
            ).includes(name),
          ) as RealtimeChannel | undefined) ?? null;
      } catch {
        existing = null;
      }
    }
    if (existing) {
      const st = (existing as unknown as { state?: string }).state;
      if (st === 'subscribed' || st === 'joined') {
        channel = existing;
        currentUserId = userId;
        isSubscribed = true;
        unsub = () => {
          try {
            existing!.unsubscribe();
          } catch {}
          try {
            client.removeChannel(existing!);
          } catch {}
          if (channel === existing) {
            channel = null;
            currentUserId = null;
          }
          isSubscribed = false;
          unsub = null;
        };
        return;
      }
      try {
        client.removeChannel(existing);
      } catch {}
    }
    if (channel) teardown();
    const ch = client.channel(name);
    ch.on<DeviceRow>(
      'postgres_changes',
      { event: '*', schema: 'public', table: 'devices', filter: `user_id=eq.${userId}` },
      async (payload: RealtimePostgresChangesPayload<DeviceRow>) => {
        if (payload.eventType === 'INSERT') {
          const r = payload.new as DeviceRow | null;
          if (r)
            opts.setDevices([
              rowToViewModel(r, opts.getHardwareId()),
              ...opts.getDevices().filter((d) => d.id !== r.id),
            ]);
        } else if (payload.eventType === 'UPDATE') {
          const r = payload.new as DeviceRow | null;
          if (r)
            opts.setDevices(
              opts
                .getDevices()
                .map((d) => (d.id === r.id ? rowToViewModel(r, opts.getHardwareId()) : d)),
            );
        } else if (payload.eventType === 'DELETE') {
          const r = payload.old as DeviceRow | null;
          if (r && (r as DeviceRow).id)
            opts.setDevices(opts.getDevices().filter((d) => d.id !== (r as DeviceRow).id));
        }
      },
    );
    ch.subscribe();
    channel = ch as unknown as RealtimeChannel;
    currentUserId = userId;
    isSubscribed = true;
    unsub = () => {
      try {
        ch.unsubscribe();
      } catch {}
      try {
        client.removeChannel(ch as unknown as RealtimeChannel);
      } catch {}
      if (channel === (ch as unknown as RealtimeChannel)) {
        channel = null;
        currentUserId = null;
      }
      isSubscribed = false;
      unsub = null;
    };
  }
  function unsubscribe(): void {
    if (unsub) {
      const fn = unsub;
      unsub = null;
      try {
        fn();
      } catch {}
    } else if (channel) teardown();
    else isSubscribed = false;
  }
  function destroy(): void {
    unsubscribe();
    if (channel) teardown();
  }
  return {
    subscribe,
    unsubscribe,
    destroy,
    get isSubscribed() {
      return isSubscribed;
    },
    get channel() {
      return channel;
    },
  };
}
export type DeviceRealtime = ReturnType<typeof createDeviceRealtime>;
