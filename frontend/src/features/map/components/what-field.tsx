import { useQuery } from '@tanstack/react-query';
import { BuildingIcon, SearchIcon } from 'lucide-react';
import { suggestWhatQueryOptions } from '../queries';
import type { WhatPick } from '../schemas';
import { sectorStyle } from '../sectors';
import { suggestionMinChars } from '../suggestions';
import { useSuggestionText } from '../use-suggestion-text';
import { Highlight } from './highlight';
import { SuggestCombobox, type SuggestGroup } from './suggest-combobox';

/** « Quoi ? »: the text typed, then the jobs of each sector, then the companies whose name matches. */
export function WhatField({
  scope,
  value,
  onValueChange,
  onPick,
  onClear,
}: {
  scope: string;
  value: string;
  onValueChange: (value: string) => void;
  onPick: (what: WhatPick) => void;
  onClear: () => void;
}) {
  const text = useSuggestionText(value);
  const { data } = useQuery({
    ...suggestWhatQueryOptions(scope, text ?? ''),
    enabled: text !== null,
  });
  const typed = value.trim();
  const picks = new Map<string, WhatPick>();
  const groups: SuggestGroup[] = [];
  if (typed.length >= suggestionMinChars) {
    picks.set('search', { q: typed, job: null });
    groups.push({
      id: 'search',
      heading: null,
      options: [
        {
          id: 'search',
          text: `Rechercher « ${typed} »`,
          content: (
            <>
              <SearchIcon aria-hidden className="size-4 shrink-0" />
              <span className="min-w-0 truncate">
                Rechercher : «{' '}
                <strong className="font-semibold">{typed}</strong> »
                {/* On a phone, the key of the keyboard already says « Rechercher ». */}
                <span className="text-muted-foreground max-sm:hidden">
                  {' '}
                  — ou appuyer sur Entrée
                </span>
              </span>
            </>
          ),
        },
      ],
    });
  }
  for (const { sector, jobs } of data?.jobs ?? []) {
    const { label, color, icon: Icon } = sectorStyle[sector];
    groups.push({
      id: `secteur-${sector}`,
      heading: (
        <>
          <Icon
            aria-hidden
            className="sector-ink size-3.5"
            style={{ '--sector': color } as React.CSSProperties}
          />
          {label}
        </>
      ),
      options: jobs.map((job, index) => {
        const id = `metier-${sector}-${index}`;
        picks.set(id, { q: '', job });
        return {
          id,
          text: `${job}, ${label}`,
          content: <Highlight text={job} query={typed} />,
        };
      }),
    });
  }
  if (data?.establishments.length)
    groups.push({
      id: 'entreprises',
      heading: 'Entreprises',
      options: data.establishments.map((establishment) => {
        const id = `etab-${establishment.siret}`;
        picks.set(id, {
          q: establishment.name,
          job: null,
          siret: establishment.siret,
        });
        return {
          id,
          text: establishment.name,
          content: (
            <>
              <BuildingIcon aria-hidden className="size-4 shrink-0" />
              <Highlight text={establishment.name} query={typed} />
            </>
          ),
        };
      }),
    });
  return (
    <SuggestCombobox
      label="Quoi ?"
      placeholder="Quoi ? Métier, entreprise, secteur"
      icon={SearchIcon}
      clearLabel="Effacer la recherche"
      value={value}
      groups={groups}
      onValueChange={onValueChange}
      onSelect={({ id }) => {
        const what = picks.get(id);
        if (what) onPick(what);
      }}
      onClear={onClear}
    />
  );
}
