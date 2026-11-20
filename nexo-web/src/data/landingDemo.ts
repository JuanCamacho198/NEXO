import type { ImageMetadata } from 'astro';
import cumbres from '@/assets/covers/cumbres-borrascosas.jpg';
import dracula from '@/assets/covers/dracula.jpg';
import montecristo from '@/assets/covers/el-conde-de-montecristo.jpg';
import frankenstein from '@/assets/covers/frankenstein.jpg';
import islaTesoro from '@/assets/covers/la-isla-del-tesoro.jpg';
import miserables from '@/assets/covers/los-miserables.jpg';
import mobyDick from '@/assets/covers/moby-dick.jpg';
import mujercitas from '@/assets/covers/mujercitas.jpg';
import odisea from '@/assets/covers/odisea.jpg';
import orgullo from '@/assets/covers/orgullo-y-prejuicio.jpg';
import type { Lang } from '@/i18n/utils';

/**
 * Content for the landing's interactive demos. It is per-language data, not UI
 * copy: the dictionary demo shows a book in the OTHER language (a Spanish
 * visitor reads the English original and looks words up in Spanish, and the
 * reverse), which is the app's real use case — Wiktionary definitions come back
 * in the app's language whatever the book's language is.
 *
 * Both texts are public domain: The Odyssey, Book I, in S. H. Butcher and
 * A. Lang's English translation (1879) and Luis Segalá y Estalella's Spanish
 * one (1910).
 */

export interface DemoWord {
  id: string;
  /** The word as it appears in the book. */
  text: string;
  partOfSpeech: string;
  definition: string;
  translation: string;
}

export type DemoSegment = string | { word: string };

export interface DictionaryDemo {
  bookLang: string;
  bookTitle: string;
  chapter: string;
  segments: DemoSegment[];
  words: DemoWord[];
  paragraphTranslation: string;
}

const dictionary: Record<Lang, DictionaryDemo> = {
  es: {
    bookLang: 'en',
    bookTitle: 'The Odyssey',
    chapter: 'Book I',
    segments: [
      'Tell me, ',
      { word: 'muse' },
      ', of that man, so ready at need, who ',
      { word: 'wandered' },
      ' far and wide, after he had ',
      { word: 'sacked' },
      ' the sacred ',
      { word: 'citadel' },
      ' of Troy, and many were the men whose towns he saw and whose mind he learnt.',
    ],
    words: [
      {
        id: 'muse',
        text: 'Muse',
        partOfSpeech: 'Sustantivo',
        definition:
          'En la mitología griega, cada una de las nueve diosas que inspiraban las artes y las ciencias.',
        translation: 'Musa',
      },
      {
        id: 'wandered',
        text: 'wandered',
        partOfSpeech: 'Verbo · pasado de «wander»',
        definition: 'Ir de un lugar a otro sin rumbo ni destino fijo.',
        translation: 'anduvo errante, vagó',
      },
      {
        id: 'sacked',
        text: 'sacked',
        partOfSpeech: 'Verbo · pasado de «sack»',
        definition: 'Robar y destruir una ciudad después de conquistarla.',
        translation: 'saqueó',
      },
      {
        id: 'citadel',
        text: 'citadel',
        partOfSpeech: 'Sustantivo',
        definition: 'Fortaleza situada en la parte alta de una ciudad para defenderla.',
        translation: 'ciudadela',
      },
    ],
    paragraphTranslation:
      'Háblame, Musa, de aquel varón de multiforme ingenio que, después de destruir la sacra ciudad de Troya, anduvo peregrinando larguísimo tiempo, vio las poblaciones y conoció las costumbres de muchos hombres.',
  },
  en: {
    bookLang: 'es',
    bookTitle: 'La Odisea',
    chapter: 'Canto I',
    segments: [
      'Háblame, ',
      { word: 'musa' },
      ', de aquel varón de multiforme ',
      { word: 'ingenio' },
      ' que, después de destruir la ',
      { word: 'sacra' },
      ' ciudad de Troya, anduvo ',
      { word: 'peregrinando' },
      ' larguísimo tiempo, vio las poblaciones y conoció las costumbres de muchos hombres.',
    ],
    words: [
      {
        id: 'musa',
        text: 'Musa',
        partOfSpeech: 'Noun',
        definition:
          'In Greek mythology, any of the nine goddesses who inspired the arts and sciences.',
        translation: 'Muse',
      },
      {
        id: 'ingenio',
        text: 'ingenio',
        partOfSpeech: 'Noun',
        definition: 'The ability to think quickly and come up with clever ideas.',
        translation: 'wit, ingenuity',
      },
      {
        id: 'sacra',
        text: 'sacra',
        partOfSpeech: 'Adjective · feminine of “sacro”',
        definition: 'Holy; set apart for a god or for worship.',
        translation: 'sacred',
      },
      {
        id: 'peregrinando',
        text: 'peregrinando',
        partOfSpeech: 'Verb · gerund of “peregrinar”',
        definition: 'Travelling through foreign lands, especially on a long journey.',
        translation: 'wandering',
      },
    ],
    paragraphTranslation:
      'Tell me, Muse, of that man, so ready at need, who wandered far and wide, after he had sacked the sacred citadel of Troy, and many were the men whose towns he saw and whose mind he learnt.',
  },
};

export function dictionaryDemo(lang: Lang): DictionaryDemo {
  return dictionary[lang];
}

/** Covers for the store tile's shelves: real edition covers, normalised to
 *  360x540 JPEG by hand (the originals were 30 KB–1.7 MB, 0.63–0.83 ratio);
 *  astro:assets serves them as small WebP/AVIF. They are decorative (the
 *  shelves are aria-hidden), so the title is only the image's identity here. */
const covers: { id: string; image: ImageMetadata }[] = [
  { id: 'odisea', image: odisea },
  { id: 'orgullo-y-prejuicio', image: orgullo },
  { id: 'moby-dick', image: mobyDick },
  { id: 'frankenstein', image: frankenstein },
  { id: 'dracula', image: dracula },
  { id: 'la-isla-del-tesoro', image: islaTesoro },
  { id: 'mujercitas', image: mujercitas },
  { id: 'los-miserables', image: miserables },
  { id: 'cumbres-borrascosas', image: cumbres },
  { id: 'el-conde-de-montecristo', image: montecristo },
];

export function storeCovers() {
  return covers;
}
