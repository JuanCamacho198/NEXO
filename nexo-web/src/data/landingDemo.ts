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

/** Public-domain classics, as the store would list them. Colours are the
 *  covers' gradient pair. */
const covers: Record<Lang, { title: string; author: string; from: string; to: string }[]> = {
  es: [
    { title: 'La Odisea', author: 'Homero', from: '#7a1f1f', to: '#3b0d12' },
    { title: 'Don Quijote', author: 'Cervantes', from: '#1f4e79', to: '#0e2442' },
    { title: 'Orgullo y prejuicio', author: 'Austen', from: '#6b5520', to: '#2b210c' },
    { title: 'Moby Dick', author: 'Melville', from: '#23523f', to: '#0d2a1f' },
    { title: 'Frankenstein', author: 'Shelley', from: '#4b2a63', to: '#1f0f2e' },
    { title: 'Drácula', author: 'Stoker', from: '#5c1830', to: '#22070f' },
    { title: 'La isla del tesoro', author: 'Stevenson', from: '#7a4a1a', to: '#2e1a07' },
    { title: 'Mujercitas', author: 'Alcott', from: '#6a2d4f', to: '#2a0f1f' },
    { title: 'Los miserables', author: 'Hugo', from: '#2c3e63', to: '#101828' },
    { title: 'Cumbres borrascosas', author: 'Brontë', from: '#3d4a2a', to: '#161c0e' },
    {
      title: 'Alicia en el país de las maravillas',
      author: 'Carroll',
      from: '#1e5d66',
      to: '#0b2428',
    },
    { title: 'El conde de Montecristo', author: 'Dumas', from: '#5a3a1a', to: '#211408' },
  ],
  en: [
    { title: 'The Odyssey', author: 'Homer', from: '#7a1f1f', to: '#3b0d12' },
    { title: 'Don Quixote', author: 'Cervantes', from: '#1f4e79', to: '#0e2442' },
    { title: 'Pride and Prejudice', author: 'Austen', from: '#6b5520', to: '#2b210c' },
    { title: 'Moby-Dick', author: 'Melville', from: '#23523f', to: '#0d2a1f' },
    { title: 'Frankenstein', author: 'Shelley', from: '#4b2a63', to: '#1f0f2e' },
    { title: 'Dracula', author: 'Stoker', from: '#5c1830', to: '#22070f' },
    { title: 'Treasure Island', author: 'Stevenson', from: '#7a4a1a', to: '#2e1a07' },
    { title: 'Little Women', author: 'Alcott', from: '#6a2d4f', to: '#2a0f1f' },
    { title: 'Les Misérables', author: 'Hugo', from: '#2c3e63', to: '#101828' },
    { title: 'Wuthering Heights', author: 'Brontë', from: '#3d4a2a', to: '#161c0e' },
    { title: 'Alice in Wonderland', author: 'Carroll', from: '#1e5d66', to: '#0b2428' },
    { title: 'The Count of Monte Cristo', author: 'Dumas', from: '#5a3a1a', to: '#211408' },
  ],
};

export function storeCovers(lang: Lang) {
  return covers[lang];
}
