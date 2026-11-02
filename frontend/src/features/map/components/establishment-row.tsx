import { MapPinOffIcon } from 'lucide-react';
import { formatDistance } from '../format';
import type { EstablishmentSummary } from '../schemas';
import { sectorStyle } from '../sectors';
import { EstablishmentVisual } from './establishment-visual';

/** Every row has this height: the text is cut or padded to it, so the list scans evenly. */
export const rowHeightClass = 'h-28';
export const rowPictureClass = 'h-full w-26 shrink-0';

/** One result of the list: a flat row under a hairline, one button, always the same height. */
export function EstablishmentRow({
  item,
  onSelect,
  onHover,
}: {
  item: EstablishmentSummary;
  onSelect: (siret: string) => void;
  onHover: (siret: string | null) => void;
}) {
  return (
    <li className="border-t first:border-t-0">
      <button
        id={`etablissement-${item.siret}`}
        type="button"
        onClick={() => onSelect(item.siret)}
        onMouseEnter={() => onHover(item.siret)}
        onMouseLeave={() => onHover(null)}
        onFocus={() => onHover(item.siret)}
        onBlur={() => onHover(null)}
        className={`flex ${rowHeightClass} w-full gap-3 text-left transition-colors outline-none hover:bg-accent focus-visible:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset motion-reduce:transition-none`}
      >
        <EstablishmentVisual
          sector={item.sector}
          imageUrl={item.imageUrl}
          showIcon={false}
          className={rowPictureClass}
        />
        <span className="flex min-w-0 flex-1 flex-col justify-center gap-1 py-2 pr-4">
          <span
            title={item.name}
            className="truncate text-sm leading-5 font-semibold"
          >
            {item.name}
          </span>
          <span className="flex h-4 items-center gap-2 text-xs leading-4">
            <span className="min-w-0 flex-1 truncate">
              {sectorStyle[item.sector].label} · {item.commune}
            </span>
            {item.distanceMeters !== null && (
              <span className="shrink-0 font-medium tabular-nums">
                {formatDistance(item.distanceMeters)}
              </span>
            )}
            {item.longitude === null && (
              <span
                className="shrink-0 text-muted-foreground"
                title="Pas de point sur la carte"
              >
                <MapPinOffIcon aria-hidden className="size-3.5" />
                <span className="sr-only">Pas de point sur la carte</span>
              </span>
            )}
          </span>
          <span className="line-clamp-2 h-8 text-xs leading-4 text-muted-foreground">
            {item.description}
          </span>
          {/* Always there, empty or not: the row keeps its height. */}
          <span className="h-4 truncate text-xs leading-4 text-muted-foreground">
            {item.jobs.join(' · ')}
          </span>
        </span>
      </button>
    </li>
  );
}
