<script lang="ts">
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
  <section aria-labelledby="discover-access-heading" class="mt-4">
    <h4 id="discover-access-heading" class="m-0 text-xs font-medium text-(--color-primary)">
      {t('discover.accessTitle')}
    </h4>
    {#each groups as entry (entry.group)}
      <div class="mt-2">
        <p class="m-0 text-micro font-medium text-(--color-text-muted)">{t(entry.labelKey)}</p>
        <ul class="m-0 mt-1 flex list-none flex-col gap-0.5 p-0">
          {#each entry.options as option (option.url)}
            <li>
              <a
                href={option.url}
                target="_blank"
                rel="noreferrer"
                class="text-sm text-(--color-primary) hover:underline"
              >
                {t(option.titleKey)}
              </a>
            </li>
          {/each}
        </ul>
      </div>
    {/each}
  </section>
{/if}
