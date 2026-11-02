import { useState } from 'react';
import { cn } from 'cn';
import { sectorStyle } from '../sectors';
import type { SectorCode } from '../schemas';

/**
 * The photo of an establishment, otherwise the free-licence picture of its sector, tinted with the
 * sector colour; a file that fails to load gives way to the next one.
 */
export function EstablishmentVisual({
  sector,
  imageUrl = null,
  showIcon = true,
  className,
}: {
  sector: SectorCode;
  imageUrl?: string | null;
  /** The sector icon over the picture; the cards of the list leave it out. */
  showIcon?: boolean;
  className?: string;
}) {
  const [broken, setBroken] = useState<ReadonlySet<string>>(new Set());
  const { color, icon: Icon } = sectorStyle[sector];
  const source = [imageUrl, `/visuels/secteurs/${sector}.webp`].find(
    (url): url is string => url !== null && !broken.has(url),
  );
  return (
    <span
      className={cn('relative block overflow-hidden', className)}
      style={{ backgroundColor: color }}
    >
      {source && (
        <img
          src={source}
          alt=""
          width={640}
          height={480}
          loading="lazy"
          decoding="async"
          onError={() => setBroken((failed) => new Set(failed).add(source))}
          className="size-full object-cover"
        />
      )}
      <span
        aria-hidden
        className="absolute inset-0"
        style={{
          background: `linear-gradient(to top, color-mix(in oklab, ${color} 85%, transparent), transparent 65%)`,
        }}
      />
      {showIcon && (
        <span
          aria-hidden
          className="sector-ink absolute bottom-3 left-4 grid size-8 place-items-center rounded-full bg-card"
          style={{ '--sector': color } as React.CSSProperties}
        >
          <Icon className="size-4" />
        </span>
      )}
    </span>
  );
}
