import { PencilIcon, Trash2Icon } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  actionDetails,
  formatActionDate,
  formatTime,
  isUpcoming,
} from '../follow-up-format';
import { actionTypeLabel, type FollowUpAction } from '../follow-up-schemas';
import { actionIcons } from './follow-up-icons';

function Entry({
  action,
  confirming,
  onEdit,
  onAskDelete,
  onCancelDelete,
  onDelete,
}: {
  action: FollowUpAction;
  confirming: boolean;
  onEdit: () => void;
  onAskDelete: () => void;
  onCancelDelete: () => void;
  onDelete: () => void;
}) {
  const Icon = actionIcons[action.type];
  const time = action.type === 'INTERVIEW' ? action.time : null;
  const upcoming = isUpcoming({ date: action.date, time });
  const when = `${formatActionDate(action.date)}${time ? ` à ${formatTime(time)}` : ''}`;
  const label = `${actionTypeLabel(action.type)}, ${when}`;
  const details = actionDetails(action);
  return (
    // The rail: a line from this dot to the next one, hidden after the last entry.
    <li className="relative flex gap-3 pb-5 before:absolute before:top-10 before:bottom-1 before:left-[1.125rem] before:w-px before:bg-border last:pb-0 last:before:hidden">
      <span
        aria-hidden
        className={
          upcoming
            ? 'relative z-10 grid size-9 shrink-0 place-items-center rounded-full border-2 border-dashed border-primary bg-background text-primary'
            : 'relative z-10 grid size-9 shrink-0 place-items-center rounded-full bg-primary text-primary-foreground'
        }
      >
        <Icon className="size-4" />
      </span>
      <div className="flex min-w-0 flex-1 flex-col gap-1.5 rounded-lg border bg-card p-3">
        <div className="flex items-start justify-between gap-2">
          <div className="flex min-w-0 flex-col gap-0.5">
            <p className="flex flex-wrap items-center gap-2 text-sm font-semibold">
              {actionTypeLabel(action.type)}
              {upcoming && <Badge variant="outline">À venir</Badge>}
            </p>
            <time
              dateTime={`${action.date}${time ? `T${time}` : ''}`}
              className="text-sm text-muted-foreground"
            >
              {when}
            </time>
          </div>
          {!confirming && (
            <div className="-mt-1.5 -mr-1.5 flex shrink-0">
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Modifier : ${label}`}
                onClick={onEdit}
              >
                <PencilIcon aria-hidden />
              </Button>
              <Button
                type="button"
                variant="ghost"
                size="icon"
                aria-label={`Supprimer : ${label}`}
                onClick={onAskDelete}
              >
                <Trash2Icon aria-hidden />
              </Button>
            </div>
          )}
        </div>
        {details.length > 0 && (
          <ul className="flex flex-wrap gap-1.5">
            {details.map((detail) => (
              <li key={detail}>
                <Badge variant="outline" className="font-normal">
                  {detail}
                </Badge>
              </li>
            ))}
          </ul>
        )}
        {action.note && (
          <p className="text-sm leading-relaxed break-words whitespace-pre-line">
            {action.note}
          </p>
        )}
        {confirming && (
          <div
            role="group"
            aria-label={`Supprimer : ${label}`}
            className="flex flex-col gap-2 rounded-md bg-muted p-3"
          >
            <p className="text-sm">Supprimer cette action ?</p>
            <div className="flex gap-2">
              <Button
                type="button"
                variant="destructive"
                size="sm"
                onClick={onDelete}
              >
                Supprimer
              </Button>
              {/* The harmless answer takes the focus. */}
              <Button
                type="button"
                variant="outline"
                size="sm"
                autoFocus
                onClick={onCancelDelete}
              >
                Garder
              </Button>
            </div>
          </div>
        )}
      </div>
    </li>
  );
}

/** The journal as a timeline, most recent at the top: what is still to come is on top of what was done. */
export function FollowUpTimeline({
  items,
  confirming,
  onEdit,
  onAskDelete,
  onCancelDelete,
  onDelete,
}: {
  items: FollowUpAction[];
  confirming: string | null;
  onEdit: (action: FollowUpAction) => void;
  onAskDelete: (action: FollowUpAction) => void;
  onCancelDelete: () => void;
  onDelete: (action: FollowUpAction) => void;
}) {
  return (
    <ol aria-label="Historique" className="flex flex-col">
      {items.map((action) => (
        <Entry
          key={action.id}
          action={action}
          confirming={confirming === action.id}
          onEdit={() => onEdit(action)}
          onAskDelete={() => onAskDelete(action)}
          onCancelDelete={onCancelDelete}
          onDelete={() => onDelete(action)}
        />
      ))}
    </ol>
  );
}
