/**
 * Daily reading quote: data, verification and deterministic rotation.
 *
 * Every entry is a factual attribution ("X wrote Y"), so it must be
 * verifiable, never remembered. `sourceUrl` points at the public-domain
 * edition (Project Gutenberg or Wikisource) where the literal text and the
 * author were confirmed. `isValidQuote` is the guard the integrity test uses:
 * a required field missing means the entry is dropped, and an empty list means
 * the About tab renders no quote block at all.
 */

export type QuoteUiLocale = 'en' | 'es';

export type ReadingQuote = {
  id: string;
  /** Literal text in its original language. Never paraphrased or shortened. */
  original: string;
  /** Original language tag: "en", "es", "la"… */
  lang: string;
  /** Faithful translations, shown only as a marked translation, never as the original. */
  translations?: Partial<Record<QuoteUiLocale, string>>;
  author: string;
  work: string;
  year: string;
  /** Verifiable public-domain source where text and author were confirmed. */
  sourceUrl: string;
  license: 'public-domain';
};

export const READING_QUOTES: readonly ReadingQuote[] = [
  {
    id: 'bacon-of-studies',
    original: 'Reading maketh a full man; conference a ready man; and writing an exact man.',
    lang: 'en',
    translations: {
      es: 'La lectura hace al hombre completo; la conversación, presto; y la escritura, exacto.',
    },
    author: 'Francis Bacon',
    work: 'Of Studies (Essays)',
    year: '1597',
    sourceUrl: 'https://www.gutenberg.org/ebooks/575',
    license: 'public-domain',
  },
  {
    id: 'dickinson-a-book',
    original: 'There is no frigate like a book\nTo take us lands away,',
    lang: 'en',
    translations: {
      es: 'No hay fragata como un libro\nque nos lleve a tierras lejanas,',
    },
    author: 'Emily Dickinson',
    work: 'Poems: Third Series — A Book',
    year: '1896',
    sourceUrl: 'https://www.gutenberg.org/ebooks/12241',
    license: 'public-domain',
  },
  {
    id: 'augustine-tolle-lege',
    original: 'tolle lege, tolle lege.',
    lang: 'la',
    translations: {
      en: 'Take up and read, take up and read.',
      es: 'Toma y lee, toma y lee.',
    },
    author: 'Agustín de Hipona',
    work: 'Confesiones, Libro VIII, cap. XII',
    year: '397–401',
    sourceUrl: 'https://la.wikisource.org/wiki/Confessiones/Liber_Octavus',
    license: 'public-domain',
  },
  {
    id: 'cervantes-quijote-ii-xxv',
    original: 'El que lee mucho y anda mucho, vee mucho y sabe mucho.',
    lang: 'es',
    translations: {
      en: 'He who reads much and travels much sees much and knows much.',
    },
    author: 'Miguel de Cervantes',
    work: 'Don Quijote de la Mancha, Segunda parte, cap. XXV',
    year: '1615',
    sourceUrl: 'https://www.gutenberg.org/ebooks/2000',
    license: 'public-domain',
  },
];

const REQUIRED_STRING_FIELDS = ['original', 'lang', 'author', 'work', 'year', 'sourceUrl'] as const;

export function isValidQuote(candidate: unknown): candidate is ReadingQuote {
  if (typeof candidate !== 'object' || candidate === null) return false;
  const quote = candidate as Partial<ReadingQuote>;
  if (quote.license !== 'public-domain') return false;
  for (const field of REQUIRED_STRING_FIELDS) {
    const value = quote[field];
    if (typeof value !== 'string' || value.trim().length === 0) return false;
  }
  return true;
}

export function validQuotes(quotes: readonly unknown[] = READING_QUOTES): ReadingQuote[] {
  return quotes.filter(isValidQuote);
}

/**
 * Locales in which this quote would render the original verbatim because no
 * translation is recorded. The quote must still render (falling back to the
 * original), but the integrity test asserts this is empty so every shipped
 * quote reads in both UI languages.
 */
export function untranslatedUiLocales(quote: ReadingQuote): QuoteUiLocale[] {
  const locales: QuoteUiLocale[] = ['en', 'es'];
  return locales.filter((locale) => {
    if (quote.lang === locale) return false;
    const translation = quote.translations?.[locale];
    return !(typeof translation === 'string' && translation.trim().length > 0);
  });
}

export function quoteDisplayText(
  quote: ReadingQuote,
  locale: QuoteUiLocale,
): { text: string; isTranslation: boolean } {
  if (quote.lang === locale) {
    return { text: quote.original, isTranslation: false };
  }
  const translation = quote.translations?.[locale];
  if (typeof translation === 'string' && translation.trim().length > 0) {
    return { text: translation, isTranslation: true };
  }
  return { text: quote.original, isTranslation: false };
}

export function dayOfYearUtc(date: Date): number {
  const startOfYear = Date.UTC(date.getUTCFullYear(), 0, 0);
  const current = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
  return Math.floor((current - startOfYear) / 86_400_000);
}

export function selectDailyQuote(quotes: readonly unknown[], date: Date): ReadingQuote | null {
  const valid = validQuotes(quotes);
  if (valid.length === 0) return null;
  const index = dayOfYearUtc(date) % valid.length;
  return valid[index] ?? null;
}
