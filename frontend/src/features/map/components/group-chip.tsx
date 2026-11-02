import { XIcon } from 'lucide-react';

/** The list narrowed to a cluster clicked on the map, and the way out of it. */
export function GroupChip({ onClear }: { onClear: () => void }) {
  return (
    <button
      type="button"
      onClick={onClear}
      aria-label="Retirer le filtre Dans ce regroupement"
      // A 44-pixel target around a smaller chip.
      className="relative inline-flex h-8 items-center gap-1 self-start rounded-full border bg-background pr-2.5 pl-3 text-sm font-medium outline-none before:absolute before:inset-x-0 before:-inset-y-1.5 before:content-[''] hover:bg-accent hover:text-accent-foreground focus-visible:ring-2 focus-visible:ring-ring"
    >
      Dans ce regroupement
      <XIcon aria-hidden className="size-4" />
    </button>
  );
}
