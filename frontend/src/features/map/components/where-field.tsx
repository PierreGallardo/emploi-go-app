import { useQuery } from '@tanstack/react-query';
import {
  BusFrontIcon,
  MapPinIcon,
  RouteIcon,
  TramFrontIcon,
} from 'lucide-react';
import { suggestWhereQueryOptions } from '../queries';
import { placeOf, type Place, type PlaceSuggestion } from '../schemas';
import { useSuggestionText } from '../use-suggestion-text';
import { Highlight } from './highlight';
import { SuggestCombobox, type SuggestGroup } from './suggest-combobox';

const sections = [
  ['COMMUNE', 'Communes'],
  ['STREET', 'Adresses'],
  ['STOP', 'Arrêts de tram et de bus'],
] as const;

const iconOf = (place: PlaceSuggestion) =>
  place.kind === 'STOP'
    ? place.mode === 'BUS'
      ? BusFrontIcon
      : TramFrontIcon
    : place.kind === 'STREET'
      ? RouteIcon
      : MapPinIcon;

/** « Où ? »: communes, then addresses, then stops; picking one searches around it. */
export function WhereField({
  scope,
  value,
  describedBy,
  invalid,
  onValueChange,
  onPick,
  onClear,
}: {
  scope: string;
  value: string;
  describedBy?: string;
  /** What was typed found no place, or the search failed. */
  invalid?: boolean;
  onValueChange: (value: string) => void;
  onPick: (place: Place) => void;
  onClear: () => void;
}) {
  const text = useSuggestionText(value);
  const { data } = useQuery({
    ...suggestWhereQueryOptions(scope, text ?? ''),
    enabled: text !== null,
  });
  const places = new Map<string, PlaceSuggestion>();
  const groups: SuggestGroup[] = sections.flatMap(([kind, heading]) => {
    const found = (data ?? []).filter((place) => place.kind === kind);
    if (found.length === 0) return [];
    return {
      id: kind.toLowerCase(),
      heading,
      options: found.map((place, index) => {
        const id = `${kind.toLowerCase()}-${index}`;
        places.set(id, place);
        const Icon = iconOf(place);
        const description =
          place.detail &&
          (place.kind === 'STOP' ? `lignes ${place.detail}` : place.detail);
        return {
          id,
          text: description ? `${place.label}, ${description}` : place.label,
          content: (
            <>
              <Icon aria-hidden className="size-4 shrink-0" />
              <span className="min-w-0 truncate">
                <Highlight text={place.label} query={value} />
                {description && (
                  <span className="text-muted-foreground">
                    {' '}
                    · {description}
                  </span>
                )}
              </span>
            </>
          ),
        };
      }),
    };
  });
  return (
    <SuggestCombobox
      label="Où ?"
      placeholder="Où ? Adresse, commune ou arrêt"
      icon={MapPinIcon}
      clearLabel="Effacer le lieu"
      describedBy={describedBy}
      invalid={invalid}
      value={value}
      groups={groups}
      onValueChange={onValueChange}
      onSelect={({ id }) => {
        const place = places.get(id);
        if (place) onPick(placeOf(place));
      }}
      onClear={onClear}
    />
  );
}
