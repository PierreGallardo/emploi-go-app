import { lazy, Suspense, useId, useState } from 'react';
import {
  BookmarkIcon,
  BriefcaseBusinessIcon,
  ClipboardCheckIcon,
  NavigationIcon,
  type LucideIcon,
} from 'lucide-react';
import { cn } from 'cn';
import type { EstablishmentDetail } from '../schemas';

function itineraryUrl(detail: EstablishmentDetail) {
  const destination =
    detail.longitude !== null && detail.latitude !== null
      ? `${detail.latitude},${detail.longitude}`
      : `${detail.name}, ${detail.address}`;
  return `https://www.google.com/maps/dir/?api=1&destination=${encodeURIComponent(destination)}`;
}

// Replaced at build time: without the flag the import is dropped and the journal is not in the bundle.
const FollowUpDialog =
  import.meta.env.VITE_LOCAL_FOLLOW_UP === 'true'
    ? lazy(() =>
        import('./follow-up-dialog').then((module) => ({
          default: module.FollowUpDialog,
        })),
      )
    : null;

const actionClass =
  'group flex min-w-0 flex-1 flex-col items-center gap-1.5 rounded-item px-1 py-2 text-center text-xs leading-4 font-medium outline-none focus-visible:ring-2 focus-visible:ring-ring';

function ActionIcon({
  icon: Icon,
  muted = false,
}: {
  icon: LucideIcon;
  muted?: boolean;
}) {
  return (
    <span
      aria-hidden
      className={cn(
        'grid size-11 place-items-center rounded-full transition-colors motion-reduce:transition-none',
        muted
          ? 'bg-muted text-muted-foreground'
          : 'bg-primary/10 text-primary group-hover:bg-primary/20',
      )}
    >
      <Icon className="size-5" />
    </span>
  );
}

function LinkAction({
  label,
  icon,
  href,
}: {
  label: string;
  icon: LucideIcon;
  href: string;
}) {
  return (
    <a
      href={href}
      target="_blank"
      rel="noopener noreferrer"
      className={actionClass}
    >
      <ActionIcon icon={icon} />
      {label}
      <span className="sr-only"> (s’ouvre dans un nouvel onglet)</span>
    </a>
  );
}

function ButtonAction({
  label,
  icon,
  onClick,
}: {
  label: string;
  icon: LucideIcon;
  onClick: () => void;
}) {
  return (
    <button type="button" className={actionClass} onClick={onClick}>
      <ActionIcon icon={icon} />
      {label}
    </button>
  );
}

/** An action that exists in the design but not yet in the app: shown, reachable, and said to be unavailable. */
function SoonAction({ label, icon }: { label: string; icon: LucideIcon }) {
  const id = useId();
  return (
    <button
      type="button"
      aria-label={label}
      aria-disabled="true"
      aria-describedby={id}
      className={cn(actionClass, 'cursor-not-allowed text-muted-foreground')}
    >
      <ActionIcon icon={icon} muted />
      {label}
      <span aria-hidden className="font-normal">
        Bientôt
      </span>
      <span id={id} className="sr-only">
        Bientôt disponible
      </span>
    </button>
  );
}

export function EstablishmentActions({
  detail,
}: {
  detail: EstablishmentDetail;
}) {
  const [followingUp, setFollowingUp] = useState(false);
  return (
    <>
      <div role="group" aria-label="Actions" className="flex border-y py-2">
        <LinkAction
          label="Itinéraire"
          icon={NavigationIcon}
          href={itineraryUrl(detail)}
        />
        {detail.jobsUrl && (
          <LinkAction
            label="Offres d’emploi"
            icon={BriefcaseBusinessIcon}
            href={detail.jobsUrl}
          />
        )}
        {FollowUpDialog ? (
          <ButtonAction
            label="Démarche"
            icon={ClipboardCheckIcon}
            onClick={() => setFollowingUp(true)}
          />
        ) : (
          <SoonAction label="Démarche" icon={ClipboardCheckIcon} />
        )}
        <SoonAction label="Enregistrer" icon={BookmarkIcon} />
      </div>
      {FollowUpDialog && followingUp && (
        <Suspense fallback={null}>
          <FollowUpDialog
            siret={detail.siret}
            name={detail.name}
            onClose={() => setFollowingUp(false)}
          />
        </Suspense>
      )}
    </>
  );
}
