import { useEffect, useRef } from 'react';
import { ChevronLeftIcon, ChevronRightIcon } from 'lucide-react';
import { cn } from 'cn';

const panelId = 'panneau-resultats';
/** The free space kept between the panel and what the map frames beside it. */
const panelGap = 12;

/**
 * The results on a large screen: a floating panel on the right of the map, which a tab on its left
 * edge slides out of sight and back. The map does not move: it only learns what the panel covers.
 */
export function SidePanel({
  label,
  collapsed,
  onToggle,
  onCover,
  children,
}: {
  label: string;
  collapsed: boolean;
  onToggle: () => void;
  /** The width the open panel covers on the right of the map, gap included. */
  onCover: (width: number) => void;
  children: React.ReactNode;
}) {
  const frame = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const panel = frame.current;
    const map = panel?.offsetParent;
    if (!panel || !(map instanceof HTMLElement)) return;
    // `offsetLeft` ignores the slide of a collapsed panel: its size when open is what matters.
    const measure = () => {
      const covered = map.clientWidth - panel.offsetLeft;
      if (covered > 0) onCover(Math.round(covered) + panelGap);
    };
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(panel);
    observer.observe(map);
    return () => observer.disconnect();
  }, [onCover]);
  const Chevron = collapsed ? ChevronLeftIcon : ChevronRightIcon;
  return (
    <div
      ref={frame}
      className={cn(
        'absolute top-(--map-inset) right-(--map-inset) bottom-(--map-inset) z-10 w-100 max-w-[calc(100%-2*var(--map-inset))] transition-transform duration-200 ease-out motion-reduce:transition-none',
        collapsed && 'translate-x-[calc(100%+var(--map-inset))]',
      )}
    >
      <aside
        id={panelId}
        aria-label={label}
        inert={collapsed}
        className="flex h-full flex-col overflow-clip rounded-surface border bg-card shadow-elevated"
      >
        {children}
      </aside>
      {/* A tab on the left edge of the panel, which stays at the right edge of the map when the panel is out. */}
      <button
        type="button"
        aria-label="Liste des établissements"
        aria-expanded={!collapsed}
        aria-controls={panelId}
        onClick={onToggle}
        className="group absolute top-1/2 right-full flex h-14 w-11 -translate-y-1/2 items-center justify-end outline-none"
      >
        <span className="grid h-14 w-6 place-items-center rounded-l-item border border-r-0 bg-card text-foreground shadow-elevated transition-colors group-hover:bg-accent group-hover:text-accent-foreground group-focus-visible:ring-2 group-focus-visible:ring-ring group-focus-visible:ring-inset motion-reduce:transition-none">
          {/* A chevron's visual mass sits toward its point: nudged a pixel the other way. */}
          <Chevron
            aria-hidden
            className={cn(
              'size-4',
              collapsed ? 'translate-x-px' : '-translate-x-px',
            )}
          />
        </span>
      </button>
    </div>
  );
}
