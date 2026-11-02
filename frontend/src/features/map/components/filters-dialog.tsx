import { useState } from 'react';
import { SlidersHorizontalIcon } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogTrigger } from '@/components/ui/dialog';
import { activeFilterCount, type Place, type SearchFilters } from '../schemas';
import { FiltersForm } from './filters-form';

/** « Filtres » under the search fields: a dialog, centred on a large screen and full screen on a phone. */
export function FiltersDialog({
  scope,
  filters,
  place,
  onApply,
}: {
  scope: string;
  filters: SearchFilters;
  place: Place | null;
  onApply: (filters: SearchFilters) => void;
}) {
  const [open, setOpen] = useState(false);
  const count = activeFilterCount(filters);
  return (
    <Dialog open={open} onOpenChange={setOpen}>
      <DialogTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="default"
          aria-label={
            count > 0
              ? `Filtres, ${count} actif${count > 1 ? 's' : ''}`
              : undefined
          }
        >
          <SlidersHorizontalIcon aria-hidden />
          Filtres
          {count > 0 && (
            <Badge className="h-5 min-w-5 justify-center px-1 tabular-nums">
              {count}
            </Badge>
          )}
        </Button>
      </DialogTrigger>
      <DialogContent className="flex h-dvh max-h-dvh w-screen max-w-none flex-col gap-0 overflow-hidden rounded-none border-0 p-0 sm:h-[min(40rem,calc(100dvh-4rem))] sm:max-w-lg sm:rounded-surface sm:border">
        <FiltersForm
          scope={scope}
          filters={filters}
          place={place}
          onApply={(next) => {
            onApply(next);
            setOpen(false);
          }}
        />
      </DialogContent>
    </Dialog>
  );
}
