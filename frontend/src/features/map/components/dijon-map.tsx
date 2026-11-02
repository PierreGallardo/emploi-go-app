import { useEffect, useRef, useState } from 'react';
import { useResolvedTheme } from '@/lib/theme';
import type {
  Insets,
  MapEngine,
  MapHover,
  MapState,
  SearchCircle,
} from '../map-engine';
import type { Coordinates, MapPoint, Datasets } from '../schemas';
import { preloadMap } from '../map-preload';

export type MapFocus = { id: string } & Coordinates;

export function DijonMap({
  points,
  pointsUnavailable,
  searchKey,
  pointsSettled,
  datasets,
  selectedId,
  highlightedId,
  circle,
  place,
  insets,
  focus,
  onPick,
  onGroup,
  onHover,
}: {
  points: MapPoint[];
  /** The points could not be read: the map says so instead of showing none. */
  pointsUnavailable: boolean;
  /** Identifies the search the points answer: the map frames them once when it changes. */
  searchKey: string;
  /** The points are those of the search, not those of the previous one. */
  pointsSettled: boolean;
  datasets: Datasets | null;
  selectedId: string | null;
  highlightedId: string | null;
  circle: SearchCircle | null;
  /** The place chosen in "Où ?": marked with a pin. */
  place: Coordinates | null;
  /** The part of the map covered by the panel or the sheet. */
  insets: Insets;
  /** An establishment the map should come to, once. */
  focus: MapFocus | null;
  onPick: (id: string) => void;
  onGroup: (ids: string[]) => void;
  onHover: (hover: MapHover | null) => void;
}) {
  const host = useRef<HTMLDivElement>(null);
  const engine = useRef<MapEngine | null>(null);
  const [state, setState] = useState<MapState>('loading');
  const theme = useResolvedTheme();
  // What the engine is created with is what the page wanted at that moment.
  const latest = useRef({
    points,
    datasets,
    selectedId,
    circle,
    place,
    theme,
    insets,
  });
  const callbacks = useRef({ onPick, onGroup, onHover });
  useEffect(() => {
    callbacks.current = { onPick, onGroup, onHover };
    latest.current = {
      points,
      datasets,
      selectedId,
      circle,
      place,
      theme,
      insets,
    };
  });
  useEffect(() => {
    engine.current?.setPoints(points);
  }, [points]);
  useEffect(() => {
    engine.current?.setDatasets(datasets);
  }, [datasets]);
  useEffect(() => {
    engine.current?.setSelected(selectedId);
  }, [selectedId]);
  useEffect(() => {
    engine.current?.setHighlighted(highlightedId);
  }, [highlightedId, points]);
  useEffect(() => {
    engine.current?.setCircle(circle);
    if (circle) engine.current?.fitCircle(circle);
  }, [circle]);
  useEffect(() => {
    engine.current?.setPlace(place);
  }, [place]);
  useEffect(() => {
    engine.current?.setInsets(insets);
  }, [insets]);
  useEffect(() => {
    engine.current?.setTheme(theme);
  }, [theme]);
  const framed = useRef(searchKey);
  useEffect(() => {
    const instance = engine.current;
    if (!instance || !pointsSettled || framed.current === searchKey) return;
    framed.current = searchKey;
    // A place chosen is framed by its circle, an establishment chosen by its fiche.
    if (!circle && !selectedId) instance.fitPoints(points);
  }, [searchKey, pointsSettled, points, circle, selectedId, state]);
  const flown = useRef<string | null>(null);
  useEffect(() => {
    if (!focus) flown.current = null;
    // Once per establishment, and only when the map exists: a refetch must not move it again.
    else if (engine.current && flown.current !== focus.id) {
      flown.current = focus.id;
      engine.current.flyTo(focus);
    }
  }, [focus, state]);
  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    const container = host.current;
    if (container) {
      preloadMap();
      void import('../map-engine')
        .then(({ createDijonMap }) => {
          if (disposed) return;
          return createDijonMap(
            container,
            {
              ...latest.current,
              onState: (status) => {
                if (!disposed) setState(status);
              },
              onPick: (id) => callbacks.current.onPick(id),
              onGroup: (ids) => callbacks.current.onGroup(ids),
              onHover: (hover) => callbacks.current.onHover(hover),
            },
            controller.signal,
          );
        })
        .then((instance) => {
          if (!instance) return;
          if (disposed) instance.destroy();
          else {
            engine.current = instance;
            instance.setPoints(latest.current.points);
            instance.setDatasets(latest.current.datasets);
          }
        })
        .catch(() => {
          if (!disposed) setState('unavailable');
        });
    }
    return () => {
      disposed = true;
      controller.abort();
      engine.current?.destroy();
      engine.current = null;
    };
  }, []);
  return (
    <section
      aria-label="Carte de Dijon Métropole"
      className="absolute inset-0 bg-muted/40"
      style={
        { '--map-bottom-inset': `${insets.bottom}px` } as React.CSSProperties
      }
    >
      <div
        ref={host}
        hidden={state === 'unavailable'}
        className="map-host size-full"
        data-map-state={state}
      />
      {state === 'loading' && (
        <p
          role="status"
          className="absolute inset-0 flex items-center justify-center text-sm text-muted-foreground"
        >
          Chargement de la carte…
        </p>
      )}
      {state === 'ready' && pointsUnavailable && (
        <p
          role="status"
          className="absolute top-3 left-16 rounded-md bg-card px-3 py-1.5 text-sm text-card-foreground shadow-elevated"
        >
          Établissements indisponibles sur la carte. La liste reste consultable.
        </p>
      )}
      {state === 'unavailable' && (
        <p
          role="alert"
          className="absolute inset-0 flex items-center justify-center px-4 text-center text-sm text-muted-foreground"
        >
          Fond de carte indisponible. La liste reste consultable.
        </p>
      )}
    </section>
  );
}
