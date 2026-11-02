import { useCallback, useMemo, useState } from 'react';
import {
  useInfiniteQuery,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import {
  detailQueryOptions,
  pointsQueryOptions,
  searchQueryOptions,
  datasetsQueryOptions,
  suggestWhereQueryOptions,
} from './queries';
import { readSearch, writeSearch } from './search-params';
import {
  effectiveSort,
  emptySearch,
  placeOf,
  type Place,
  type SearchFilters,
  type SearchRequest,
  type What,
  type WhatPick,
  type Sort,
} from './schemas';

type Patch = { filters?: SearchFilters; selectedId?: string | null };

/** The whole state of the search: the URL for what is shareable, memory for the visitor's position. */
export function useMapSearch(scope: string) {
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const { filters, selectedId } = useMemo(() => readSearch(params), [params]);
  const [place, setPlace] = useState<Place | null>(null);
  const [group, setGroup] = useState<string[] | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const request: SearchRequest = useMemo(
    () => ({ filters, place, sirets: group }),
    [filters, place, group],
  );
  const list = useInfiniteQuery(searchQueryOptions(scope, request));
  const points = useQuery(pointsQueryOptions(scope, request));
  const detail = useQuery(detailQueryOptions(scope, selectedId));
  const datasets = useQuery(datasetsQueryOptions(scope));
  // What makes a search new for the map: not the sort or the radius, which only reorder or reach the same results.
  const searchKey = useMemo(
    () =>
      JSON.stringify({
        ...filters,
        sort: null,
        radiusKm: 0,
        place: place && [place.lng, place.lat],
      }),
    [filters, place],
  );
  const items = useMemo(
    () => list.data?.pages.flatMap((page) => page.items) ?? [],
    [list.data],
  );

  const go = useCallback(
    (patch: Patch, replace = false) =>
      setParams(
        writeSearch(
          patch.filters ?? filters,
          patch.selectedId === undefined ? selectedId : patch.selectedId,
        ),
        { replace },
      ),
    [filters, selectedId, setParams],
  );
  // A new search drops what narrowed the previous one: the group of a click on the map.
  const restart = (patch: Patch, replace = false) => {
    setGroup(null);
    go({ selectedId: null, ...patch }, replace);
  };

  return {
    filters,
    selectedId,
    place,
    group,
    scope,
    message,
    list,
    items,
    total: list.data?.pages[0]?.total,
    points: points.data ?? [],
    pointsUnavailable: points.isError,
    searchKey,
    pointsSettled: points.isSuccess && !points.isPlaceholderData,
    datasets: datasets.data ?? null,
    detail,
    sort: effectiveSort(filters, place),
    changeFilters: (next: SearchFilters) => restart({ filters: next }, true),
    changeSort: (sort: Sort) => go({ filters: { ...filters, sort } }, true),
    changeRadius: (radiusKm: number) =>
      go({ filters: { ...filters, radiusKm } }, true),
    clearEverything: () => {
      setPlace(null);
      setMessage(null);
      restart({ filters: { ...emptySearch, radiusKm: filters.radiusKm } });
    },
    select: (id: string) => go({ selectedId: id }),
    back: () => go({ selectedId: null }, true),
    showGroup: (ids: string[]) => {
      setGroup(ids);
      go({ selectedId: null });
    },
    clearGroup: () => setGroup(null),
    /** Enter in a field: what was typed becomes the search; a job title picked earlier is kept while the field is untouched. */
    submit: async ({ q, job, where }: What & { where: string }) => {
      setMessage(null);
      let next = place;
      if (!where) next = null;
      else if (where !== place?.label) {
        try {
          const found = await queryClient.fetchQuery(
            suggestWhereQueryOptions(scope, where),
          );
          const first = found[0];
          next = first ? placeOf(first) : null;
          if (!next)
            setMessage(
              'Lieu introuvable. Essaie une rue, une commune ou un arrêt.',
            );
        } catch {
          next = null;
          setMessage('La recherche de lieu est indisponible. Réessaie.');
        }
      }
      setPlace(next);
      restart({ filters: { ...filters, q, job } });
    },
    /** A suggestion of "Où ?": the map goes there and the search runs from there. */
    pickPlace: (picked: Place) => {
      setMessage(null);
      setPlace(picked);
      restart({});
    },
    /** « Où ? » emptied: no pin, no circle, and the search is no longer around a place. */
    clearPlace: () => {
      setMessage(null);
      setPlace(null);
      restart({});
    },
    /** A suggestion of "Quoi ?": a job title or a name, searched at once; a company opens its fiche. */
    pickWhat: ({ siret, ...what }: WhatPick) => {
      restart({
        filters: { ...filters, ...what },
        selectedId: siret ?? null,
      });
    },
  };
}

export type MapSearch = ReturnType<typeof useMapSearch>;
