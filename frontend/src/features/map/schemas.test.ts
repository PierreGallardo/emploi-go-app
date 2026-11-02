import { expect, it } from 'vitest';
import { activeFilterCount, effectiveSort, emptySearch } from './schemas';

const place = { label: 'x', lng: 5, lat: 47.3 };

it.each([
  {
    cas: 'une position choisie',
    filters: { q: '', job: null, sort: null },
    place,
    attendu: 'distance',
  },
  {
    cas: 'une recherche tapée sans position',
    filters: { q: 'boulanger', job: null, sort: null },
    place: null,
    attendu: 'relevance',
  },
  {
    cas: 'un métier choisi sans position',
    filters: { q: '', job: 'Boulanger', sort: null },
    place: null,
    attendu: 'relevance',
  },
  {
    cas: 'ni position ni recherche',
    filters: { q: '', job: null, sort: null },
    place: null,
    attendu: 'name',
  },
  {
    cas: 'un tri choisi',
    filters: { q: 'a', job: null, sort: 'name' as const },
    place,
    attendu: 'name',
  },
  {
    cas: 'un tri par distance sans position',
    filters: { q: '', job: null, sort: 'distance' as const },
    place: null,
    attendu: 'relevance',
  },
])('trie par $attendu pour $cas', ({ filters, place, attendu }) => {
  expect(effectiveSort(filters, place)).toBe(attendu);
});

it('compte les filtres actifs sans la recherche, le tri ni le rayon', () => {
  expect(activeFilterCount(emptySearch)).toBe(0);
  expect(
    activeFilterCount({
      ...emptySearch,
      q: 'x',
      sort: 'name',
      radiusKm: 2,
      job: 'Boulanger',
      sectors: ['FOOD', 'RETAIL'],
      types: ['TEMP_AGENCY'],
      sizes: ['SMALL', 'LARGE'],
      transit: true,
    }),
  ).toBe(6);
});
