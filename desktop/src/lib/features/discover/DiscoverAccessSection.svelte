<script lang="ts">
  import ExternalLink from 'lucide-svelte/icons/external-link';
  import type { MessageKey } from '$lib/shared/i18n/messages.en';
  import type { AccessGroup, LegalAccess } from '$lib/shared/services/catalog';

  type Translate = (key: MessageKey, params?: Record<string, string | number>) => string;

  let { access, t }: { access: LegalAccess; t: Translate } = $props();

  const GROUP_ORDER: readonly AccessGroup[] = ['FREE', 'BUY', 'SUBSCRIBE'];

  const GROUP_LABEL: Record<AccessGroup, MessageKey> = {
    FREE: 'discover.accessGroupFree',
    BUY: 'discover.accessGroupBuy',
    SUBSCRIBE: 'discover.accessGroupSubscribe',
  };

  /**
   * External web options grouped FREE → BUY → SUBSCRIBE. The public-domain
   * in-app download is filtered out here because `DiscoverDetail` owns it as
   * the download CTA (single source of truth for the transfer state machine).
   */
  const groups = $derived(
    GROUP_ORDER.map((group) => ({
      group,
      labelKey: GROUP_LABEL[group],
      options: access.options.filter((option) => option.group === group && !option.opensInApp),
    })).filter((entry) => entry.options.length > 0),
  );
</script>

{#if groups.length > 0}
  <section
    aria-labelledby="discover-access-heading"
    class="mt-6 border-t border-(--color-edge) pt-5"
  >
    <h4 id="discover-access-heading" class="m-0 text-base font-semibold text-(--color-primary)">
      {t('discover.accessTitle')}
    </h4>
    <!-- One row per group: the group name left, its places as pills right. -->
    <div class="mt-3 flex flex-col gap-3">
      {#each groups as entry (entry.group)}
        <div class="flex flex-wrap items-center gap-x-4 gap-y-2">
          <p
            class="m-0 w-28 shrink-0 text-xs font-semibold tracking-[0.08em] text-(--color-text-muted) uppercase"
          >
            {t(entry.labelKey)}
          </p>
          <ul class="m-0 flex list-none flex-wrap gap-2 p-0">
            {#each entry.options as option (option.url)}
              <li>
                <a
                  href={option.url}
                  target="_blank"
                  rel="noreferrer"
                  class="inline-flex items-center gap-1.5 rounded-full border border-(--color-edge) bg-(--color-surface-subtle) px-3.5 py-1.5 text-sm font-medium text-(--color-primary) transition-colors hover:border-(--color-brand)/60 hover:text-(--color-brand-text)"
                >
                  {t(option.titleKey)}
                  <ExternalLink size={13} strokeWidth={2} aria-hidden="true" />
                </a>
              </li>
            {/each}
          </ul>
        </div>
      {/each}
    </div>
  </section>
{/if}
