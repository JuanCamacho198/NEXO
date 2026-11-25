import { describe, expect, it } from 'vitest';
import { isShouting, splitTocTitle } from '$lib/features/reader/chrome/tocTitle';

describe('splitTocTitle', () => {
  it('takes a leading number into its own column', () => {
    expect(splitTocTitle('1. En la línea de salida')).toEqual({
      number: '1',
      label: null,
      title: 'En la línea de salida',
      shouting: false,
    });
    expect(splitTocTitle('IV) The road').number).toBe('IV');
  });

  it('takes a short label before a full stop', () => {
    const parts = splitTocTitle('Primera parte. DEL EDÉN A CAJAMARCA');
    expect(parts.label).toBe('Primera parte');
    expect(parts.title).toBe('DEL EDÉN A CAJAMARCA');
    expect(parts.shouting).toBe(true);
    expect(splitTocTitle('PRÓLOGO. La pregunta de Yali')).toMatchObject({
      label: 'PRÓLOGO',
      title: 'La pregunta de Yali',
      shouting: false,
    });
  });

  it('leaves abbreviations and long sentences whole', () => {
    expect(splitTocTitle('Dr. No').label).toBeNull();
    expect(splitTocTitle('St. Ives and the sea').label).toBeNull();
    expect(
      splitTocTitle('Esta es una frase demasiado larga para ser un rótulo. Y sigue').label,
    ).toBeNull();
  });

  it('flags shouting capitals, not acronyms or short words', () => {
    expect(isShouting('AGRADECIMIENTOS')).toBe(true);
    expect(isShouting('Cubierta')).toBe(false);
    expect(isShouting('La ONU y la OTAN')).toBe(false);
    expect(isShouting('XII')).toBe(false);
  });
});
