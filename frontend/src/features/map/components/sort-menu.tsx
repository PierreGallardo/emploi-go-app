import { ArrowDownUpIcon, ChevronDownIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
} from '@/components/ui/dropdown-menu';
import type { Sort } from '../schemas';

const sortLabels: Record<Sort, string> = {
  relevance: 'Pertinence',
  distance: 'Distance',
  name: 'Ordre alphabétique',
};

export function SortMenu({
  sort,
  hasPlace,
  onSort,
}: {
  sort: Sort;
  hasPlace: boolean;
  onSort: (sort: Sort) => void;
}) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button
          type="button"
          variant="outline"
          size="default"
          aria-label={`Trier par : ${sortLabels[sort]}`}
        >
          <ArrowDownUpIcon aria-hidden />
          {sortLabels[sort]}
          <ChevronDownIcon aria-hidden />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end">
        <DropdownMenuRadioGroup
          value={sort}
          onValueChange={(value) => onSort(value as Sort)}
        >
          <DropdownMenuRadioItem value="relevance">
            {sortLabels.relevance}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="distance" disabled={!hasPlace}>
            {sortLabels.distance}
            {!hasPlace && ' (choisis un lieu)'}
          </DropdownMenuRadioItem>
          <DropdownMenuRadioItem value="name">
            {sortLabels.name}
          </DropdownMenuRadioItem>
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
