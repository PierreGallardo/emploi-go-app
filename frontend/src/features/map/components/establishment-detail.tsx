import { useEffect, useId, useRef } from 'react';
import {
  ArrowLeftIcon,
  BusFrontIcon,
  MapPinIcon,
  TramFrontIcon,
} from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { formatDistance } from '../format';
import type { EstablishmentDetail } from '../schemas';
import { CompanyFacts } from './company-facts';
import { EstablishmentActions } from './establishment-actions';
import { sectorStyle } from '../sectors';
import { EstablishmentVisual } from './establishment-visual';

/** The open fiche, which the skip link of the page reaches in place of the hidden search. */
export const ficheId = 'fiche';

function Section({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-2">
      <h3 id={id} className="text-base font-semibold">
        {title}
      </h3>
      {children}
    </section>
  );
}

function DetailContent({
  detail,
  heading,
}: {
  detail: EstablishmentDetail;
  heading: React.Ref<HTMLHeadingElement>;
}) {
  return (
    <article className="flex flex-col">
      <EstablishmentVisual
        sector={detail.sector}
        imageUrl={detail.imageUrl}
        className="aspect-video w-full"
      />
      <div className="flex flex-col gap-5 px-4 pt-4 pb-6">
        <header className="flex flex-col gap-2">
          <h2
            ref={heading}
            tabIndex={-1}
            className="text-xl leading-7 font-bold outline-none"
          >
            {detail.name}
          </h2>
          <p className="text-sm text-muted-foreground">
            {sectorStyle[detail.sector].label}
          </p>
          {detail.description && (
            <p className="text-sm leading-6">{detail.description}</p>
          )}
        </header>
        <div className="flex flex-col gap-2 text-sm">
          <div className="flex flex-wrap gap-x-4 gap-y-2">
            <p className="flex min-w-0 flex-1 basis-44 gap-2">
              <MapPinIcon aria-hidden className="mt-0.5 size-4 shrink-0" />
              {detail.address}
            </p>
            {detail.nearestStop && (
              <p className="flex min-w-0 flex-1 basis-44 gap-2">
                {detail.nearestStop.mode === 'TRAM' ? (
                  <TramFrontIcon
                    aria-hidden
                    className="mt-0.5 size-4 shrink-0"
                  />
                ) : (
                  <BusFrontIcon
                    aria-hidden
                    className="mt-0.5 size-4 shrink-0"
                  />
                )}
                Arrêt Divia {detail.nearestStop.name}, à{' '}
                {formatDistance(detail.nearestStop.distanceMeters)}
              </p>
            )}
          </div>
          {detail.longitude === null && (
            <p className="text-muted-foreground">
              Cet établissement n’a pas de point sur la carte.
            </p>
          )}
        </div>
        <EstablishmentActions detail={detail} />
        {detail.jobs.length > 0 && (
          // The ROME / NAF table gives the jobs of a sector, not the positions of this company.
          <Section title="Métiers de cette activité">
            <ul className="list-disc pl-5 text-sm marker:text-muted-foreground">
              {detail.jobs.map((job) => (
                <li key={job}>{job}</li>
              ))}
            </ul>
          </Section>
        )}
        <Section title="Entreprise">
          <CompanyFacts detail={detail} />
        </Section>
      </div>
    </article>
  );
}

/** One establishment in the panel, in place of the list, with the way back to it. */
export function EstablishmentDetailView({
  detail,
  loading,
  failed,
  onBack,
  onRetry,
}: {
  detail: EstablishmentDetail | undefined;
  loading: boolean;
  failed: boolean;
  onBack: () => void;
  onRetry: () => void;
}) {
  const heading = useRef<HTMLHeadingElement>(null);
  const back = useRef<HTMLButtonElement>(null);
  const id = detail?.siret;
  // The row that had the focus is hidden: the way back holds it until the name of the fiche can.
  // The name sits under the picture: the fiche opens at its top, without a scroll to it.
  useEffect(() => {
    (heading.current ?? back.current)?.focus({ preventScroll: true });
  }, [id]);
  return (
    <div
      id={ficheId}
      tabIndex={-1}
      className="flex min-h-0 flex-1 flex-col outline-none"
    >
      <div className="flex h-15 shrink-0 items-center border-b px-1.5">
        <Button ref={back} type="button" variant="ghost" onClick={onBack}>
          <ArrowLeftIcon aria-hidden />
          Retour à la liste
        </Button>
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        {/* Always there, so that the end of the loading is announced too. */}
        <p role="status" className="sr-only">
          {loading ? 'Chargement de la fiche…' : ''}
        </p>
        {failed ? (
          <div className="flex flex-col gap-3 p-4">
            <Alert variant="destructive">
              <AlertDescription>
                Cet établissement est indisponible pour le moment.
              </AlertDescription>
            </Alert>
            <Button type="button" variant="outline" onClick={onRetry}>
              Réessayer
            </Button>
          </div>
        ) : loading || !detail ? (
          <div aria-hidden className="flex flex-col">
            <Skeleton className="aspect-video w-full rounded-none" />
            <div className="flex flex-col gap-2 px-4 pt-4">
              <Skeleton className="h-7 w-3/4" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-5/6" />
            </div>
          </div>
        ) : (
          <DetailContent detail={detail} heading={heading} />
        )}
      </div>
    </div>
  );
}
