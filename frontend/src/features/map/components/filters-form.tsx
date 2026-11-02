import { useEffect, useId, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeftIcon, ChevronRightIcon } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Checkbox } from '@/components/ui/checkbox';
import { DialogDescription, DialogTitle } from '@/components/ui/dialog';
import { Label } from '@/components/ui/label';
import { Slider } from '@/components/ui/slider';
import { companyTypes, establishmentSizes } from '../filter-options';
import { countQueryOptions } from '../queries';
import {
  activeFilterCount,
  defaultRadiusKm,
  emptySearch,
  maxRadiusKm,
  minRadiusKm,
  transitMaxMeters,
  type Place,
  type SearchFilters,
} from '../schemas';
import { sectorCodes, sectorStyle } from '../sectors';

const gutter = 'px-4 sm:px-6';

const toggle = <T,>(items: T[], item: T, on: boolean) =>
  on ? [...items, item] : items.filter((other) => other !== item);

const companies = (total: number) =>
  `${total} entreprise${total > 1 ? 's' : ''}`;

function Option({
  checked,
  onChange,
  description,
  children,
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  description?: string;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <div className="flex min-h-11 items-center gap-3">
      <Checkbox
        id={id}
        checked={checked}
        onCheckedChange={(value) => onChange(value === true)}
      />
      <Label
        htmlFor={id}
        className="flex flex-1 flex-col items-start gap-0.5 py-2.5 text-sm font-normal"
      >
        <span className="flex items-center gap-2">{children}</span>
        {description && (
          <span className="text-muted-foreground">{description}</span>
        )}
      </Label>
    </div>
  );
}

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <fieldset
      className={`flex flex-col border-b py-4 last:border-b-0 ${gutter}`}
    >
      <legend className="float-left mb-1 w-full text-base font-semibold">
        {title}
      </legend>
      {children}
    </fieldset>
  );
}

function SectorsPage({
  sectors,
  onChange,
}: {
  sectors: SearchFilters['sectors'];
  onChange: (sectors: SearchFilters['sectors']) => void;
}) {
  return (
    <div
      role="group"
      aria-label="Secteurs d’activité"
      className={`flex flex-col py-2 ${gutter}`}
    >
      {sectorCodes.map((code) => {
        const { label, color, icon: Icon } = sectorStyle[code];
        return (
          <Option
            key={code}
            checked={sectors.includes(code)}
            onChange={(on) => onChange(toggle(sectors, code, on))}
          >
            <Icon
              aria-hidden
              className="sector-ink size-4"
              style={{ '--sector': color } as React.CSSProperties}
            />
            {label}
          </Option>
        );
      })}
    </div>
  );
}

function MainPage({
  draft,
  hasPlace,
  sectorsButton,
  onChange,
  onSectors,
}: {
  draft: SearchFilters;
  hasPlace: boolean;
  sectorsButton: React.Ref<HTMLButtonElement>;
  onChange: (patch: Partial<SearchFilters>) => void;
  onSectors: () => void;
}) {
  const count = draft.sectors.length;
  const radiusHelp = useId();
  return (
    <>
      <button
        ref={sectorsButton}
        type="button"
        aria-label={`Secteurs d’activité${count > 0 ? `, ${count} sélectionné${count > 1 ? 's' : ''}` : ''}`}
        onClick={onSectors}
        className={`flex min-h-14 w-full items-center justify-between gap-3 border-b py-3 text-left outline-none hover:bg-accent focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset ${gutter}`}
      >
        <span className="text-base font-semibold">Secteurs d’activité</span>
        <span className="flex items-center gap-2">
          {count > 0 && (
            <span
              aria-hidden
              className="grid size-6 place-items-center rounded-full bg-foreground text-xs font-semibold text-background tabular-nums"
            >
              {count}
            </span>
          )}
          <ChevronRightIcon aria-hidden className="size-5" />
        </span>
      </button>
      <Section title="Rayon">
        <div className="flex h-11 items-center gap-4">
          <Slider
            thumbLabel="Rayon autour du lieu"
            thumbValueText={`${draft.radiusKm} km`}
            min={minRadiusKm}
            max={maxRadiusKm}
            step={1}
            value={[draft.radiusKm]}
            onValueChange={([km]) => onChange({ radiusKm: km })}
            disabled={!hasPlace}
            thumbDescribedBy={hasPlace ? undefined : radiusHelp}
          />
          {/* The thumb already reads the value out: the figure is for the eyes. */}
          <output
            aria-hidden
            className="w-12 shrink-0 text-right text-sm font-medium tabular-nums"
          >
            {draft.radiusKm} km
          </output>
        </div>
        {!hasPlace && (
          <p id={radiusHelp} className="text-sm text-muted-foreground">
            Choisis d’abord un lieu dans « Où ? » pour chercher autour de lui.
          </p>
        )}
      </Section>
      <Section title="Type d’entreprise">
        {companyTypes.map(([code, label]) => (
          <Option
            key={code}
            checked={draft.types.includes(code)}
            onChange={(on) =>
              onChange({ types: toggle(draft.types, code, on) })
            }
          >
            {label}
          </Option>
        ))}
      </Section>
      <Section title="Taille de l’entreprise">
        {establishmentSizes.map(([code, label]) => (
          <Option
            key={code}
            checked={draft.sizes.includes(code)}
            onChange={(on) =>
              onChange({ sizes: toggle(draft.sizes, code, on) })
            }
          >
            {label}
          </Option>
        ))}
      </Section>
      <Section title="Déplacements">
        <Option
          checked={draft.transit}
          onChange={(transit) => onChange({ transit })}
          description={`Un arrêt de tram ou de bus Divia à moins de ${transitMaxMeters} m.`}
        >
          Accessible en transports
        </Option>
      </Section>
    </>
  );
}

type Page = 'main' | 'sectors';

/** The content of the filters dialog: a draft of the filters, applied only by the button of its footer. */
export function FiltersForm({
  scope,
  filters,
  place,
  onApply,
}: {
  scope: string;
  filters: SearchFilters;
  place: Place | null;
  onApply: (filters: SearchFilters) => void;
}) {
  const [draft, setDraft] = useState(filters);
  const [page, setPage] = useState<Page>('main');
  const heading = useRef<HTMLHeadingElement>(null);
  const sectorsButton = useRef<HTMLButtonElement>(null);
  // A new page moves the focus to its title, the way back to the button that left; opening the dialog leaves it to Radix.
  const shown = useRef<Page>('main');
  useEffect(() => {
    if (shown.current === page) return;
    shown.current = page;
    (page === 'main' ? sectorsButton : heading).current?.focus();
  }, [page]);
  const { data: total } = useQuery(
    countQueryOptions(scope, { filters: draft, place, sirets: null }),
  );
  const patch = (next: Partial<SearchFilters>) =>
    setDraft((current) => ({ ...current, ...next }));
  const clear = () => {
    setDraft((current) => ({
      ...emptySearch,
      q: current.q,
      job: current.job,
      sort: current.sort,
    }));
    // The button turns disabled under the focus: the title takes it.
    heading.current?.focus();
  };
  const hasDraft =
    activeFilterCount(draft) > 0 || draft.radiusKm !== defaultRadiusKm;
  return (
    <>
      <header
        className={`flex h-15 shrink-0 items-center gap-1 border-b pr-14 ${gutter}`}
      >
        {page === 'sectors' && (
          <Button
            type="button"
            variant="ghost"
            size="icon"
            aria-label="Retour aux filtres"
            className="-ml-3.5"
            onClick={() => setPage('main')}
          >
            <ArrowLeftIcon aria-hidden className="size-5" />
          </Button>
        )}
        <DialogTitle ref={heading} tabIndex={-1} className="outline-none">
          {page === 'main' ? 'Filtres' : 'Secteurs d’activité'}
        </DialogTitle>
        <DialogDescription className="sr-only">
          Affine les entreprises affichées. Le bouton du bas applique les
          filtres.
        </DialogDescription>
      </header>
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {page === 'sectors' ? (
          <SectorsPage
            sectors={draft.sectors}
            onChange={(sectors) => patch({ sectors })}
          />
        ) : (
          <MainPage
            draft={draft}
            hasPlace={place !== null}
            sectorsButton={sectorsButton}
            onChange={patch}
            onSectors={() => setPage('sectors')}
          />
        )}
      </div>
      <footer
        className={`flex shrink-0 items-center justify-between gap-3 border-t py-3 ${gutter}`}
      >
        <Button
          type="button"
          variant="ghost"
          className="-ml-4"
          onClick={clear}
          disabled={!hasDraft}
        >
          Tout effacer
        </Button>
        <Button
          type="button"
          disabled={total === 0}
          onClick={() => onApply(draft)}
        >
          {total === undefined
            ? 'Afficher les entreprises'
            : total === 0
              ? 'Aucune entreprise'
              : `Afficher ${companies(total)}`}
        </Button>
      </footer>
      <div role="status" className="sr-only">
        {total === undefined ? '' : `${companies(total)} avec ces filtres`}
      </div>
    </>
  );
}
