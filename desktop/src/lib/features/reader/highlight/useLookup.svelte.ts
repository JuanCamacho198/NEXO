import type { TranslationOutcome } from '$lib/shared/ports/TranslationPort';
import type { DefinitionResult } from '$lib/shared/ports/DictionaryLookupPort';
import type { SourceOrigin } from '$lib/shared/services/translation/translationLanguageResolution';

/**
 * Translate and Define as ONE lookup with two tabs.
 *
 * They used to be two independent panels that could both be open, stacked
 * under the menu. A reader who translated a word and then wanted its meaning
 * (or the other way round) had to close one and open the other. Here a single
 * panel holds both, the reader moves between them with the tabs, and each
 * side is fetched once, on first view, and kept while the panel is open.
 *
 * Shared by the selection toolbar (a new selection) and the highlight menu (an
 * existing highlight); each owns one instance and renders `LookupPanel`.
 */
export type LookupTab = 'translate' | 'define';

export type LanguagePair = { source: string | null; target: string | null };

export type LookupDeps = {
  translate: () => Promise<TranslationOutcome>;
  define: () => Promise<DefinitionResult>;
  /** Persists a pair picked in the panel; the lookup re-translates after it. */
  changeLanguages: (next: LanguagePair & { changed: 'source' | 'target' }) => Promise<void>;
  /** False when there is no text to look up (nothing selected, blank). */
  canTranslate: () => boolean;
  /** False outside the definable range (a sentence, digits, punctuation). */
  canDefine: () => boolean;
};

export type Lookup = {
  readonly tab: LookupTab | null;
  readonly open: boolean;
  readonly translation: TranslationOutcome | null;
  readonly translating: boolean;
  readonly translationActive: boolean;
  readonly overlayIsAhead: boolean;
  readonly shownLanguages: LanguagePair | null;
  readonly shownSourceOrigin: SourceOrigin | null;
  readonly resolvedLanguages: LanguagePair | null;
  readonly definition: DefinitionResult | null;
  readonly defining: boolean;
  readonly definitionFailed: boolean;
  canDefine(): boolean;
  select(next: LookupTab): void;
  toggle(next: LookupTab): void;
  close(): void;
  changeLanguages(next: LanguagePair & { changed: 'source' | 'target' }): Promise<void>;
};

export function createLookup(deps: LookupDeps): Lookup {
  let tab = $state<LookupTab | null>(null);

  let translation = $state<TranslationOutcome | null>(null);
  let translating = $state(false);
  /** A language change is re-translating (the bar hides while it does). */
  let translationActive = $state(false);
  /** The pair the reader just picked, ahead of the answer for it. */
  let pickedLanguages = $state<LanguagePair | null>(null);

  let definition = $state<DefinitionResult | null>(null);
  let defining = $state(false);
  let definitionFailed = $state(false);

  // A closed panel must not be reopened by an answer that was still in
  // flight: each close starts a new generation and stale answers are dropped.
  let generation = 0;

  const pairOutcome = $derived(
    translation?.status === 'ok' || translation?.status === 'refused' ? translation : null,
  );
  const overlayIsAhead = $derived(pairOutcome !== null && pickedLanguages !== null);
  const shownLanguages = $derived<LanguagePair | null>(
    pairOutcome === null
      ? null
      : overlayIsAhead
        ? pickedLanguages
        : { source: pairOutcome.source, target: pairOutcome.target },
  );
  const shownSourceOrigin = $derived<SourceOrigin | null>(
    pairOutcome === null ? null : overlayIsAhead ? 'preference' : pairOutcome.sourceOrigin,
  );
  const resolvedLanguages = $derived<LanguagePair | null>(
    pairOutcome === null ? null : { source: pairOutcome.source, target: pairOutcome.target },
  );

  async function loadTranslation(): Promise<void> {
    if (translation || translating || !deps.canTranslate()) return;
    const gen = generation;
    translating = true;
    try {
      const outcome = await deps.translate();
      if (gen === generation) translation = outcome;
    } finally {
      if (gen === generation) translating = false;
    }
  }

  async function loadDefinition(): Promise<void> {
    if (definition || definitionFailed || defining || !deps.canDefine()) return;
    const gen = generation;
    defining = true;
    try {
      const result = await deps.define();
      if (gen === generation) definition = result;
    } catch {
      if (gen === generation) definitionFailed = true;
    } finally {
      if (gen === generation) defining = false;
    }
  }

  /** Shows a tab, fetching its side the first time it is shown. */
  function select(next: LookupTab): void {
    if (next === 'translate' && !deps.canTranslate()) return;
    if (next === 'define' && !deps.canDefine()) return;
    tab = next;
    void (next === 'translate' ? loadTranslation() : loadDefinition());
  }

  /** The menu buttons: open on that tab, switch to it, or close it if shown. */
  function toggle(next: LookupTab): void {
    if (tab === next) close();
    else select(next);
  }

  function close(): void {
    generation += 1;
    tab = null;
    translation = null;
    translating = false;
    translationActive = false;
    pickedLanguages = null;
    definition = null;
    defining = false;
    definitionFailed = false;
  }

  async function changeLanguages(
    next: LanguagePair & { changed: 'source' | 'target' },
  ): Promise<void> {
    pickedLanguages = { source: next.source, target: next.target };
    await deps.changeLanguages(next);
    if (!deps.canTranslate()) return;
    const gen = generation;
    translating = true;
    translationActive = true;
    try {
      const outcome = await deps.translate();
      if (gen === generation) translation = outcome;
    } finally {
      if (gen === generation) {
        translating = false;
        translationActive = false;
        pickedLanguages = null;
      }
    }
  }

  return {
    get tab() {
      return tab;
    },
    get open() {
      return tab !== null;
    },
    get translation() {
      return translation;
    },
    get translating() {
      return translating;
    },
    get translationActive() {
      return translationActive;
    },
    get overlayIsAhead() {
      return overlayIsAhead;
    },
    get shownLanguages() {
      return shownLanguages;
    },
    get shownSourceOrigin() {
      return shownSourceOrigin;
    },
    get resolvedLanguages() {
      return resolvedLanguages;
    },
    get definition() {
      return definition;
    },
    get defining() {
      return defining;
    },
    get definitionFailed() {
      return definitionFailed;
    },
    canDefine: () => deps.canDefine(),
    select,
    toggle,
    close,
    changeLanguages,
  };
}
