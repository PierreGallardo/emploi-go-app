import { useRef, useState } from 'react';
import { ArrowLeftIcon, ClipboardCheckIcon, PlusIcon } from 'lucide-react';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogTitle,
} from '@/components/ui/dialog';
import type { FollowUpAction } from '../follow-up-schemas';
import { useFollowUp } from '../use-follow-up';
import { FollowUpForm } from './follow-up-form';
import { FollowUpTimeline } from './follow-up-timeline';

type Editing = 'new' | FollowUpAction | null;

function Journal({ siret, name }: { siret: string; name: string }) {
  const { actions, failed, clearFailure, add, update, remove } =
    useFollowUp(siret);
  const title = useRef<HTMLHeadingElement>(null);
  const [editing, setEditing] = useState<Editing>(null);
  const [confirming, setConfirming] = useState<string | null>(null);
  const backToJournal = () => {
    setEditing(null);
    setConfirming(null);
    // A new screen: its title takes the focus, as in the filters.
    title.current?.focus();
  };
  const startForm = (next: Editing) => {
    clearFailure();
    setConfirming(null);
    setEditing(next);
    requestAnimationFrame(() => title.current?.focus());
  };
  // The entry stays on screen when the browser refused it.
  const keep = (kept: boolean) => {
    if (kept) backToJournal();
  };
  return (
    <>
      <header className="flex shrink-0 flex-col gap-1 border-b px-3 py-2 pr-14">
        <div className="flex items-center gap-2">
          {editing && (
            <Button
              type="button"
              variant="ghost"
              size="icon"
              aria-label="Retour à l’historique"
              onClick={backToJournal}
            >
              <ArrowLeftIcon aria-hidden />
            </Button>
          )}
          <DialogTitle
            ref={title}
            tabIndex={-1}
            className="px-2 text-lg leading-snug outline-none"
          >
            {editing === null
              ? `Démarche avec ${name}`
              : editing === 'new'
                ? 'Ajouter une action'
                : 'Modifier l’action'}
          </DialogTitle>
        </div>
        <DialogDescription className="px-2 text-xs">
          Données de test : ta démarche est gardée seulement sur cet appareil,
          dans ce navigateur.
        </DialogDescription>
      </header>
      {failed && (
        <Alert variant="destructive" role="alert" className="mx-5 mt-4 w-auto">
          <AlertDescription>
            Ce changement n’a pas pu être enregistré. Réessaie.
          </AlertDescription>
        </Alert>
      )}
      {editing ? (
        <FollowUpForm
          action={editing === 'new' ? undefined : editing}
          onCancel={backToJournal}
          onSubmit={(body) =>
            keep(editing === 'new' ? add(body) : update(editing.id, body))
          }
        />
      ) : (
        <>
          <div className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
            {actions.length === 0 ? (
              <div className="flex flex-col items-center gap-3 px-2 py-10 text-center">
                <span
                  aria-hidden
                  className="grid size-14 place-items-center rounded-full bg-primary/10 text-primary"
                >
                  <ClipboardCheckIcon className="size-7" />
                </span>
                <p className="text-base font-semibold">
                  Tu n’as encore rien fait avec {name}.
                </p>
                <p className="max-w-xs text-sm text-muted-foreground">
                  Note ici tes candidatures, tes relances et tes rendez-vous
                  pour t’y retrouver.
                </p>
                <Button type="button" onClick={() => startForm('new')}>
                  <PlusIcon aria-hidden />
                  Ajouter une action
                </Button>
              </div>
            ) : (
              <FollowUpTimeline
                items={actions}
                confirming={confirming}
                onEdit={startForm}
                onAskDelete={(action) => {
                  clearFailure();
                  setConfirming(action.id);
                }}
                onCancelDelete={() => setConfirming(null)}
                onDelete={(action) => keep(remove(action.id))}
              />
            )}
          </div>
          {/* The round button of the app floats over the bottom right corner of a phone: the footer keeps its action clear of it. */}
          {actions.length > 0 && (
            <footer className="flex shrink-0 justify-end border-t bg-popover py-3 pr-20 pl-5 sm:pr-5">
              <Button type="button" onClick={() => startForm('new')}>
                <PlusIcon aria-hidden />
                Ajouter une action
              </Button>
            </footer>
          )}
        </>
      )}
    </>
  );
}

/** The journal of what the visitor did with one establishment, kept in the browser: a dialog, centred on a large screen and full screen on a phone. */
export function FollowUpDialog({
  siret,
  name,
  onClose,
}: {
  siret: string;
  name: string;
  onClose: () => void;
}) {
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="flex h-dvh max-h-dvh w-screen max-w-none flex-col gap-0 overflow-hidden rounded-none p-0 sm:h-[min(42rem,88dvh)] sm:max-h-[88dvh] sm:w-full sm:max-w-xl sm:rounded-xl">
        <Journal siret={siret} name={name} />
      </DialogContent>
    </Dialog>
  );
}
