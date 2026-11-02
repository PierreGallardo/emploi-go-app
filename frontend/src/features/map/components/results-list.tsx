import { useEffect, useId, useRef } from 'react';
import { SearchXIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  Empty,
  EmptyContent,
  EmptyDescription,
  EmptyHeader,
  EmptyMedia,
  EmptyTitle,
} from '@/components/ui/empty';
import { Skeleton } from '@/components/ui/skeleton';
import { resultCount } from '../format';
import type { EstablishmentSummary } from '../schemas';
import {
  EstablishmentRow,
  rowHeightClass,
  rowPictureClass,
} from './establishment-row';

function RowSkeletons() {
  return (
    <ul aria-hidden className="flex flex-col">
      {Array.from({ length: 5 }, (_, index) => (
        <li
          key={index}
          className={`flex ${rowHeightClass} gap-3 border-t first:border-t-0`}
        >
          <Skeleton className={`${rowPictureClass} rounded-none`} />
          <div className="flex flex-1 flex-col justify-center gap-2 py-2 pr-4">
            <Skeleton className="h-4 w-3/4" />
            <Skeleton className="h-3 w-1/2" />
            <Skeleton className="h-3 w-full" />
            <Skeleton className="h-3 w-2/3" />
          </div>
        </li>
      ))}
    </ul>
  );
}

export function ResultsList({
  items,
  total,
  status,
  hasNextPage,
  fetchingNextPage,
  suggestions,
  scrollRoot,
  onSelect,
  onHover,
  onMore,
  onRetry,
  onClear,
}: {
  items: EstablishmentSummary[];
  total: number | undefined;
  status: 'pending' | 'error' | 'success';
  hasNextPage: boolean;
  fetchingNextPage: boolean;
  /** What to try when nothing matches. */
  suggestions: string[];
  scrollRoot: React.RefObject<HTMLDivElement | null>;
  onSelect: (id: string) => void;
  onHover: (id: string | null) => void;
  onMore: () => void;
  onRetry: () => void;
  onClear: () => void;
}) {
  const countId = useId();
  const sentinel = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const target = sentinel.current;
    if (!target || !hasNextPage || fetchingNextPage) return;
    if (typeof IntersectionObserver === 'undefined') return;
    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry?.isIntersecting) onMore();
      },
      { root: scrollRoot.current, rootMargin: '200px' },
    );
    observer.observe(target);
    return () => observer.disconnect();
  }, [hasNextPage, fetchingNextPage, onMore, scrollRoot, items.length]);
  return (
    <div className="flex flex-col pb-4">
      <div className="flex h-11 shrink-0 items-center px-4">
        <p
          id={countId}
          role="status"
          aria-live="polite"
          className="text-sm font-medium"
        >
          {status === 'pending'
            ? 'Recherche en cours…'
            : status === 'error' || total === undefined
              ? ''
              : resultCount(total)}
        </p>
      </div>
      {status === 'pending' ? (
        <RowSkeletons />
      ) : status === 'error' ? (
        <Empty role="alert" className="mx-4 md:p-8">
          <EmptyHeader>
            <EmptyTitle>Les établissements sont indisponibles</EmptyTitle>
            <EmptyDescription>
              Vérifie ta connexion puis réessaie.
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button type="button" onClick={onRetry}>
              Réessayer
            </Button>
          </EmptyContent>
        </Empty>
      ) : items.length === 0 ? (
        <Empty className="mx-4 md:p-8">
          <EmptyHeader>
            <EmptyMedia variant="icon">
              <SearchXIcon aria-hidden />
            </EmptyMedia>
            <EmptyTitle>Aucun établissement trouvé</EmptyTitle>
            <EmptyDescription>
              {suggestions.length > 0
                ? suggestions.join(' ')
                : 'Essaie un autre mot ou une autre adresse.'}
            </EmptyDescription>
          </EmptyHeader>
          <EmptyContent>
            <Button type="button" variant="outline" onClick={onClear}>
              Tout effacer
            </Button>
          </EmptyContent>
        </Empty>
      ) : (
        <>
          <ul aria-labelledby={countId} className="flex flex-col">
            {items.map((item) => (
              <EstablishmentRow
                key={item.siret}
                item={item}
                onSelect={onSelect}
                onHover={onHover}
              />
            ))}
          </ul>
          <div ref={sentinel} aria-hidden className="h-px" />
          {hasNextPage && (
            <Button
              type="button"
              variant="outline"
              // Not disabled: the button keeps the focus while the next page loads.
              aria-disabled={fetchingNextPage}
              onClick={fetchingNextPage ? undefined : onMore}
              className="mt-4 self-center"
            >
              {fetchingNextPage ? 'Chargement…' : 'Afficher plus'}
            </Button>
          )}
        </>
      )}
    </div>
  );
}
