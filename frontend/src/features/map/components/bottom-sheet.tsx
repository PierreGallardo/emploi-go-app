import { useRef, useState } from 'react';
import { cn } from 'cn';

export const sheetSnaps = ['collapsed', 'half', 'full'] as const;
export type SheetSnap = (typeof sheetSnaps)[number];

/** The handle: a 44-pixel target, whatever the bar it shows. */
const handleHeight = 44;
/** The strip of map left above the sheet at its highest position. */
const topGap = 8;
/** A drag longer than this, in pixels, goes to the next position that way. */
const flingDistance = 60;

/**
 * The heights of the three positions of the sheet, for a map of this height: the lowest leaves the
 * handle and the search in sight, as measured.
 */
export function sheetHeights(
  containerHeight: number,
  searchHeight: number,
): [number, number, number] {
  const full = Math.max(containerHeight - topGap, 0);
  const collapsed = Math.min(handleHeight + searchHeight, full);
  return [
    collapsed,
    Math.min(Math.max(Math.round(containerHeight * 0.55), collapsed), full),
    full,
  ];
}

/**
 * A standard bottom sheet: it never hides or locks the map, which stays usable above it and does
 * not move. It follows the finger while dragged and settles on the nearest position; its handle is
 * also a button, so that no gesture is required to open or close it.
 */
export function BottomSheet({
  label,
  heights,
  snap,
  onSnap,
  children,
}: {
  label: string;
  heights: [number, number, number];
  snap: SheetSnap;
  onSnap: (snap: SheetSnap) => void;
  children: React.ReactNode;
}) {
  const [dragHeight, setDragHeight] = useState<number | null>(null);
  const start = useRef<{ y: number; height: number; moved: boolean } | null>(
    null,
  );
  const index = sheetSnaps.indexOf(snap);
  const height = dragHeight ?? heights[index]!;
  const settle = (final: number, distance: number) => {
    const nearest = heights
      .map((value, position) => ({ position, gap: Math.abs(value - final) }))
      .sort((a, b) => a.gap - b.gap)[0]!.position;
    const target =
      Math.abs(distance) > flingDistance
        ? Math.min(2, Math.max(0, index + Math.sign(distance)))
        : nearest;
    onSnap(sheetSnaps[target]!);
  };
  return (
    <section
      aria-label={label}
      style={{ height }}
      className={cn(
        // Clipped, not hidden: a focus inside must never scroll the sheet itself out of its frame.
        'absolute inset-x-0 bottom-0 z-10 flex flex-col overflow-clip rounded-t-surface border-t bg-card shadow-elevated',
        dragHeight === null &&
          'transition-[height] duration-200 ease-out motion-reduce:transition-none',
      )}
    >
      <button
        type="button"
        aria-label="Liste des établissements"
        aria-expanded={snap !== 'collapsed'}
        title="Faire glisser pour agrandir ou réduire la liste"
        onClick={(event) => {
          // The click that ends a drag must not also cycle the position; a key press is never a drag.
          const dragged = event.detail !== 0 && start.current?.moved;
          start.current = null;
          if (!dragged) onSnap(sheetSnaps[(index + 1) % sheetSnaps.length]!);
        }}
        onPointerDown={(event) => {
          event.currentTarget.setPointerCapture(event.pointerId);
          start.current = { y: event.clientY, height, moved: false };
        }}
        onPointerMove={(event) => {
          const origin = start.current;
          if (!origin) return;
          const distance = origin.y - event.clientY;
          if (Math.abs(distance) > 4) origin.moved = true;
          if (origin.moved)
            setDragHeight(
              Math.min(
                heights[2],
                Math.max(heights[0], origin.height + distance),
              ),
            );
        }}
        onPointerUp={(event) => {
          const origin = start.current;
          if (origin?.moved) {
            const distance = origin.y - event.clientY;
            settle(origin.height + distance, distance);
          }
          setDragHeight(null);
        }}
        onPointerCancel={() => {
          setDragHeight(null);
          start.current = null;
        }}
        style={{ height: handleHeight }}
        className="flex w-full shrink-0 touch-none items-center justify-center outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
      >
        <span
          aria-hidden
          className="h-1.5 w-9 rounded-full bg-muted-foreground/70"
        />
      </button>
      <div className="flex min-h-0 flex-1 flex-col">{children}</div>
    </section>
  );
}
