import { useQuery } from '@tanstack/react-query';
import { summariesQueryOptions } from '../queries';
import { sectorStyle } from '../sectors';
import type { MapHover } from '../map-engine';
import { EstablishmentVisual } from './establishment-visual';

const width = 256;
const gap = 12;

/**
 * What a mouse rests on, beside the point or the cluster: a picture, a name, a sector and a commune,
 * or the names of a small cluster. Only a help for the mouse: the list is the accessible alternative,
 * so it is hidden from assistive technology and never takes the focus.
 */
export function MapTooltip({
  scope,
  hover,
  mapWidth,
  reservedRight,
}: {
  scope: string;
  hover: MapHover | null;
  mapWidth: number;
  /** Width of the panel over the right of the map: the tooltip does not go under it. */
  reservedRight: number;
}) {
  const ids =
    hover?.kind === 'point'
      ? [hover.id]
      : hover?.kind === 'cluster' && hover.ids
        ? hover.ids
        : [];
  const { data } = useQuery(summariesQueryOptions(scope, ids));
  if (!hover) return null;
  // Right of the marker, or left of it when there is no room.
  const flip = hover.x + hover.radius + gap + width > mapWidth - reservedRight;
  const style = {
    top: hover.y,
    left: flip ? hover.x - hover.radius - gap : hover.x + hover.radius + gap,
    width,
    transform: `translate(${flip ? '-100%' : '0'}, -50%)`,
  };
  const found = data ?? [];
  const body =
    hover.kind === 'point' ? (
      found[0] && (
        <div className="flex h-24 items-stretch">
          <EstablishmentVisual
            sector={found[0].sector}
            imageUrl={found[0].imageUrl}
            showIcon={false}
            className="w-20 shrink-0 self-stretch [&_img]:absolute [&_img]:inset-0"
          />
          <div className="flex min-w-0 flex-col justify-center p-3">
            <span className="line-clamp-2 text-sm leading-snug font-semibold">
              {found[0].name}
            </span>
            <span className="mt-0.5 line-clamp-1 text-xs text-muted-foreground">
              {sectorStyle[found[0].sector].label} · {found[0].commune}
            </span>
          </div>
        </div>
      )
    ) : hover.ids === null || hover.count > hover.ids.length ? (
      hover.ids === null && hover.count <= 4 ? null : (
        <p className="flex min-h-14 items-center p-3 text-sm font-medium">
          {hover.count} entreprises · cliquer pour zoomer
        </p>
      )
    ) : (
      found.length > 0 && (
        <ul className="flex min-h-14 flex-col justify-center gap-1 p-3">
          {found.map((item) => (
            <li key={item.siret} className="truncate text-sm font-medium">
              {item.name}
            </li>
          ))}
        </ul>
      )
    );
  if (!body) return null;
  return (
    <div
      aria-hidden
      data-testid="map-tooltip"
      style={style}
      className="pointer-events-none absolute z-20 overflow-hidden rounded-surface border bg-popover text-popover-foreground shadow-elevated"
    >
      {body}
    </div>
  );
}
