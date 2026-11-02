import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useMediaQuery, wideScreen } from '@/lib/use-media-query';
import type { Insets, MapHover, SearchCircle } from '../map-engine';
import { positionOf } from '../schemas';
import { useMapSearch } from '../use-map-search';
import {
  BottomSheet,
  sheetHeights,
  sheetSnaps,
  type SheetSnap,
} from './bottom-sheet';
import { DijonMap, type MapFocus } from './dijon-map';
import { ficheId } from './establishment-detail';
import { MapTooltip } from './map-tooltip';
import { ResultsPanel, searchId } from './results-panel';
import { SidePanel } from './side-panel';

const label = 'Établissements';
const noInsets: Insets = { top: 0, right: 0, bottom: 0, left: 0 };

export function MapScreen({ scope }: { scope: string }) {
  const search = useMapSearch(scope);
  const desktop = useMediaQuery(wideScreen, true);
  const screen = useRef<HTMLDivElement>(null);
  const [size, setSize] = useState({ width: 0, height: 0 });
  const [hover, setHover] = useState<MapHover | null>(null);
  const [snap, setSnap] = useState<SheetSnap>('half');
  const [collapsed, setCollapsed] = useState(false);
  const [highlighted, setHighlighted] = useState<string | null>(null);
  // Measured before the map, which is created once its style has loaded, reads them.
  const [panelCover, setPanelCover] = useState(0);
  const [searchHeight, setSearchHeight] = useState(0);
  useEffect(() => {
    const element = screen.current;
    if (!element) return;
    const observer = new ResizeObserver(([entry]) => {
      if (entry)
        setSize({
          width: entry.contentRect.width,
          height: entry.contentRect.height,
        });
    });
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  const heights = useMemo(
    () => sheetHeights(size.height, searchHeight),
    [size.height, searchHeight],
  );
  const covered = desktop && !collapsed ? panelCover : 0;
  const insets = useMemo<Insets>(
    () =>
      desktop
        ? { ...noInsets, right: covered }
        : { ...noInsets, bottom: heights[sheetSnaps.indexOf(snap)]! },
    [desktop, covered, heights, snap],
  );
  const { place } = search;
  const radiusKm = search.filters.radiusKm;
  const circle = useMemo<SearchCircle | null>(
    () => (place ? { lng: place.lng, lat: place.lat, km: radiusKm } : null),
    [place, radiusKm],
  );
  const placePoint = useMemo(
    () => (place ? { lng: place.lng, lat: place.lat } : null),
    [place],
  );
  const longitude = search.detail.data?.longitude ?? null;
  const latitude = search.detail.data?.latitude ?? null;
  const focus = useMemo<MapFocus | null>(() => {
    const position = positionOf({ longitude, latitude });
    return search.selectedId && position
      ? { id: search.selectedId, ...position }
      : null;
  }, [search.selectedId, longitude, latitude]);
  const { select, showGroup } = search;
  // A click on a point opens the establishment where the visitor can see it: the sheet or the panel is raised.
  const pick = useCallback(
    (id: string) => {
      select(id);
      setCollapsed(false);
      setSnap((current) => (current === 'collapsed' ? 'half' : current));
    },
    [select],
  );
  const content = (
    <ResultsPanel
      search={search}
      listHidden={!desktop && snap === 'collapsed'}
      onHover={setHighlighted}
      onSearchHeight={desktop ? undefined : setSearchHeight}
    />
  );
  return (
    <div ref={screen} className="relative min-h-0 flex-1 overflow-hidden">
      {/* The map comes first on the page: a keyboard reaches the panel without crossing it. */}
      <a
        href={`#${search.selectedId === null ? searchId : ficheId}`}
        className="sr-only focus:not-sr-only focus:absolute focus:top-3 focus:left-16 focus:z-30 focus:rounded-md focus:bg-card focus:px-3 focus:py-2 focus:text-sm focus:font-medium focus:shadow-elevated focus:ring-2 focus:ring-ring focus:outline-none"
      >
        {search.selectedId === null
          ? 'Aller à la recherche'
          : 'Aller à la fiche'}
      </a>
      <DijonMap
        points={search.points}
        pointsUnavailable={search.pointsUnavailable}
        searchKey={search.searchKey}
        pointsSettled={search.pointsSettled}
        datasets={search.datasets}
        selectedId={search.selectedId}
        highlightedId={highlighted}
        circle={circle}
        place={placePoint}
        insets={insets}
        focus={focus}
        onPick={pick}
        onGroup={showGroup}
        onHover={setHover}
      />
      <MapTooltip
        scope={scope}
        hover={hover}
        mapWidth={size.width}
        reservedRight={covered}
      />
      {desktop ? (
        <SidePanel
          label={label}
          collapsed={collapsed}
          onToggle={() => setCollapsed((value) => !value)}
          onCover={setPanelCover}
        >
          {content}
        </SidePanel>
      ) : (
        <BottomSheet
          label={label}
          heights={heights}
          snap={snap}
          onSnap={setSnap}
        >
          {content}
        </BottomSheet>
      )}
    </div>
  );
}
