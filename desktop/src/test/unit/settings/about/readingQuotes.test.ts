import { describe, expect, it } from 'vitest';
import {
  READING_QUOTES,
  dayOfYearUtc,
  isValidQuote,
  quoteDisplayText,
  selectDailyQuote,
  untranslatedUiLocales,
  validQuotes,
  type ReadingQuote,
} from '$lib/features/settings/about/readingQuotes';

const REQUIRED: ReadonlyArray<keyof ReadingQuote> = [
  'author',
  'work',
  'year',
  'sourceUrl',
  'original',
];

describe('reading quote integrity', () => {
  it('ships at least one verified public-domain quote', () => {
    expect(READING_QUOTES.length).toBeGreaterThan(0);
  });

  it('every quote carries author, work, year, sourceUrl, original, lang and license', () => {
    for (const quote of READING_QUOTES) {
      for (const field of REQUIRED) {
        const value = quote[field];
        expect(typeof value, `${quote.id}.${field}`).toBe('string');
        expect((value as string).trim().length, `${quote.id}.${field}`).toBeGreaterThan(0);
      }
      expect(quote.lang.trim().length, `${quote.id}.lang`).toBeGreaterThan(0);
      expect(quote.license, `${quote.id}.license`).toBe('public-domain');
      expect(quote.sourceUrl, `${quote.id}.sourceUrl`).toMatch(/^https:\/\//);
      expect(isValidQuote(quote), `${quote.id} must validate`).toBe(true);
    }
  });

  it('rejects a quote with no source so an unverified quote cannot ship', () => {
    const { sourceUrl: _omitted, ...withoutSource } = READING_QUOTES[0] as ReadingQuote;
    expect(isValidQuote(withoutSource)).toBe(false);
    expect(validQuotes([withoutSource])).toHaveLength(0);
  });

  it('rejects a quote without a public-domain license', () => {
    expect(isValidQuote({ ...READING_QUOTES[0], license: 'unknown' })).toBe(false);
  });

  it('renders in both UI languages: original when it matches, otherwise a translation', () => {
    for (const quote of READING_QUOTES) {
      expect(untranslatedUiLocales(quote), `${quote.id} must reach en and es`).toEqual([]);
    }
  });
});

describe('daily rotation', () => {
  it('is stable for a given date', () => {
    const date = new Date(Date.UTC(2026, 9, 4));
    expect(selectDailyQuote(READING_QUOTES, date)?.id).toBe(
      selectDailyQuote(READING_QUOTES, date)?.id,
    );
  });

  it('walks the whole list across consecutive days', () => {
    const seen = new Set<string>();
    const start = Date.UTC(2026, 0, 1);
    for (let offset = 0; offset < READING_QUOTES.length; offset += 1) {
      const quote = selectDailyQuote(READING_QUOTES, new Date(start + offset * 86_400_000));
      if (quote) seen.add(quote.id);
    }
    expect(seen.size).toBe(READING_QUOTES.length);
  });

  it('repeats the same quote on the same day of year', () => {
    const a = selectDailyQuote(READING_QUOTES, new Date(Date.UTC(2026, 5, 15)));
    const b = selectDailyQuote(READING_QUOTES, new Date(Date.UTC(2027, 5, 15)));
    expect(a?.id).toBe(b?.id);
  });

  it('returns null when no valid quote exists so the block stays hidden', () => {
    expect(selectDailyQuote([], new Date())).toBeNull();
    expect(selectDailyQuote([{ id: 'broken' }], new Date())).toBeNull();
  });

  it('computes the day of year in UTC', () => {
    expect(dayOfYearUtc(new Date(Date.UTC(2026, 0, 1)))).toBe(1);
    expect(dayOfYearUtc(new Date(Date.UTC(2026, 11, 31)))).toBe(365);
    expect(dayOfYearUtc(new Date(Date.UTC(2026, 1, 1)))).toBe(32);
  });
});

describe('translation marking', () => {
  it('shows the original unchanged when the quote language matches the UI', () => {
    const esQuote = READING_QUOTES.find((quote) => quote.lang === 'es');
    expect(esQuote).toBeDefined();
    if (!esQuote) return;
    const display = quoteDisplayText(esQuote, 'es');
    expect(display.isTranslation).toBe(false);
    expect(display.text).toBe(esQuote.original);
  });

  it('returns the translation and marks it when the quote language differs', () => {
    const enQuote = READING_QUOTES.find((quote) => quote.lang === 'en');
    expect(enQuote).toBeDefined();
    if (!enQuote) return;
    const display = quoteDisplayText(enQuote, 'es');
    expect(display.isTranslation).toBe(true);
    expect(display.text).toBe(enQuote.translations?.es);
    expect(display.text).not.toBe(enQuote.original);
  });

  it('falls back to the original (never hides the text) when no translation exists', () => {
    const quote: ReadingQuote = {
      ...(READING_QUOTES[0] as ReadingQuote),
      lang: 'la',
      translations: {},
    };
    const display = quoteDisplayText(quote, 'es');
    expect(display.isTranslation).toBe(false);
    expect(display.text).toBe(quote.original);
  });
});
