import { expect, it } from 'vitest';
import { maxTextLength, readSearch, writeSearch } from './search-params';
import { emptySearch, type SearchFilters } from './schemas';

const read = (query: string) => readSearch(new URLSearchParams(query));

it('lit une recherche complète dans l’adresse', () => {
  const { filters, selectedId } = read(
    'q=boulanger&metier=Pilote+fictif&secteur=FOOD&secteur=RETAIL&type=TEMP_AGENCY&taille=SMALL&taille=LARGE&transport=1&tri=name&rayon=6&etablissement=12345678900012',
  );

  expect(filters).toEqual({
    q: 'boulanger',
    job: 'Pilote fictif',
    sectors: ['FOOD', 'RETAIL'],
    types: ['TEMP_AGENCY'],
    sizes: ['SMALL', 'LARGE'],
    transit: true,
    sort: 'name',
    radiusKm: 6,
  });
  expect(selectedId).toBe('12345678900012');
});

it.each([
  {
    cas: 'un secteur inconnu',
    query: 'secteur=NOPE',
    champ: 'sectors',
    attendu: [],
  },
  {
    cas: 'un doublon de secteur',
    query: 'secteur=FOOD&secteur=FOOD',
    champ: 'sectors',
    attendu: ['FOOD'],
  },
  {
    cas: 'un type d’entreprise inconnu',
    query: 'type=NOPE&type=COMPANY',
    champ: 'types',
    attendu: ['COMPANY'],
  },
  {
    cas: 'une taille inconnue',
    query: 'taille=GIANT',
    champ: 'sizes',
    attendu: [],
  },
  { cas: 'un métier vide', query: 'metier=%20', champ: 'job', attendu: null },
  {
    cas: 'un rayon au-delà du maximum',
    query: 'rayon=7',
    champ: 'radiusKm',
    attendu: 5,
  },
  { cas: 'un tri inconnu', query: 'tri=hasard', champ: 'sort', attendu: null },
  {
    cas: 'un rayon hors choix',
    query: 'rayon=999',
    champ: 'radiusKm',
    attendu: 5,
  },
  {
    cas: 'un indicateur autre que 1',
    query: 'transport=oui',
    champ: 'transit',
    attendu: false,
  },
] as const)(
  'ignore $cas et retombe sur la valeur par défaut',
  ({ query, champ, attendu }) => {
    expect(read(query).filters[champ]).toEqual(attendu);
  },
);

it.each([
  {
    cas: 'un identifiant qui n’en est pas un',
    query: 'etablissement=abc',
    champ: 'selectedId',
  },
] as const)('ignore $cas', ({ query }) => {
  expect(read(query).selectedId).toBeNull();
});

it('limite la recherche à la longueur d’un champ', () => {
  expect(read(`q=${'a'.repeat(maxTextLength + 1)}`).filters.q).toHaveLength(
    maxTextLength,
  );
});

it('n’écrit dans l’adresse que ce qui s’écarte des valeurs par défaut', () => {
  expect(writeSearch(emptySearch, null).toString()).toBe('');
  const written = writeSearch(
    {
      ...emptySearch,
      q: 'plombier',
      sectors: ['CONSTRUCTION'],
      radiusKm: 2,
      transit: true,
    },
    '12345678900012',
  );

  expect(written.get('q')).toBe('plombier');
  expect(written.getAll('secteur')).toEqual(['CONSTRUCTION']);
  expect(written.get('rayon')).toBe('2');
  expect(written.get('transport')).toBe('1');
});

it('relit ce qu’elle a écrit', () => {
  const filters: SearchFilters = {
    ...emptySearch,
    q: 'a b',
    job: 'Pilote fictif',
    types: ['IT_SERVICES'],
    sizes: ['MEDIUM'],
    transit: true,
  };

  expect(readSearch(writeSearch(filters, null)).filters).toEqual(filters);
});
