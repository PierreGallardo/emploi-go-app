import { useEffect, useRef } from 'react';
import { activeFilterCount } from '../schemas';
import type { MapSearch } from '../use-map-search';
import { EstablishmentDetailView } from './establishment-detail';
import { FiltersDialog } from './filters-dialog';
import { GroupChip } from './group-chip';
import { ResultsList } from './results-list';
import { SearchForm } from './search-form';
import { SortMenu } from './sort-menu';

/** The search of the map, which the skip link of the page reaches. */
export const searchId = 'recherche';

function suggestionsFor(search: MapSearch) {
  return [
    (search.filters.q || search.filters.job) && 'Essaie un autre mot.',
    activeFilterCount(search.filters) > 0 && 'Retire un filtre.',
    search.place && 'Élargis le rayon de recherche.',
  ].filter((text): text is string => Boolean(text));
}

/** The search, its filters, the results and the fiche of one establishment, whatever the layout around them. */
export function ResultsPanel({
  search,
  listHidden = false,
  onHover,
  onSearchHeight,
}: {
  search: MapSearch;
  /** The list is out of sight (the bottom sheet at its lowest position): only the search shows. */
  listHidden?: boolean;
  onHover: (id: string | null) => void;
  /** The height of the search above the list, each time it changes: the lowest sheet shows it all. */
  onSearchHeight?: (height: number) => void;
}) {
  const scroll = useRef<HTMLDivElement>(null);
  const searchArea = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const element = searchArea.current;
    if (!element || !onSearchHeight) return;
    // A hidden search (a fiche is open) measures nothing: the sheet keeps its last height.
    const observer = new ResizeObserver(() => {
      if (element.offsetHeight > 0) onSearchHeight(element.offsetHeight);
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, [onSearchHeight]);
  const detailOpen = search.selectedId !== null;
  // The list stays mounted under a fiche: its scroll position is the one the way back returns to.
  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className={detailOpen ? 'hidden' : 'contents'}>
        <div
          ref={searchArea}
          id={searchId}
          tabIndex={-1}
          className="flex shrink-0 flex-col gap-2 p-4 outline-none"
        >
          <SearchForm
            scope={search.scope}
            q={search.filters.q}
            job={search.filters.job}
            place={search.place}
            message={search.message}
            onSubmit={(submission) => void search.submit(submission)}
            onPickWhat={search.pickWhat}
            onPickPlace={search.pickPlace}
            onClearPlace={search.clearPlace}
          />
          <div className="flex items-center justify-between gap-2">
            <FiltersDialog
              scope={search.scope}
              filters={search.filters}
              place={search.place}
              onApply={search.changeFilters}
            />
            <SortMenu
              sort={search.sort}
              hasPlace={search.place !== null}
              onSort={search.changeSort}
            />
          </div>
          {search.filters.job && (
            // The table links a job to activities, not to a company: the wording says so.
            <p className="text-sm text-muted-foreground">
              Établissements dont l’activité correspond au métier «{' '}
              {search.filters.job} ».
            </p>
          )}
          {search.group && <GroupChip onClear={search.clearGroup} />}
        </div>
        <div
          ref={scroll}
          hidden={listHidden}
          className="min-h-0 flex-1 overflow-y-auto overscroll-contain border-t"
        >
          <ResultsList
            items={search.items}
            total={search.total}
            status={search.list.status}
            hasNextPage={search.list.hasNextPage}
            fetchingNextPage={search.list.isFetchingNextPage}
            suggestions={suggestionsFor(search)}
            scrollRoot={scroll}
            onSelect={search.select}
            onHover={onHover}
            onMore={() => void search.list.fetchNextPage()}
            onRetry={() => void search.list.refetch()}
            onClear={search.clearEverything}
          />
        </div>
      </div>
      {detailOpen && (
        <EstablishmentDetailView
          detail={search.detail.data}
          loading={search.detail.isPending}
          failed={search.detail.isError}
          onBack={() => {
            const id = search.selectedId;
            search.back();
            // The row the visitor came from takes the focus back; opened from the map, it may not be in
            // the list, which may also be empty or failed: then the search does.
            requestAnimationFrame(() =>
              (
                document.getElementById(`etablissement-${id}`) ??
                document.querySelector<HTMLElement>('[id^="etablissement-"]') ??
                searchArea.current
              )?.focus(),
            );
          }}
          onRetry={() => void search.detail.refetch()}
        />
      )}
    </div>
  );
}
