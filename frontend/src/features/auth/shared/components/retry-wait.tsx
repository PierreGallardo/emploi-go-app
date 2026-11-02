import { useEffect, useId, useState, type ComponentProps } from 'react';
import { cn } from 'cn';
import { Button } from '@/components/ui/button';
import { retryAtOf } from '../../errors';

const timeOfDay = new Intl.DateTimeFormat('fr-FR', {
  hour: '2-digit',
  minute: '2-digit',
});

/** Rounds the deadline up to the minute: the time shown is never earlier than the real end of the wait. */
function timeAfterWait(retryAt: number) {
  return timeOfDay.format(Math.ceil(retryAt / 60_000) * 60_000);
}

export const retryOverMessage = 'Tu peux réessayer.';

/** `waiting` until `retryAt` (ms since epoch), then `over`; nothing is retried automatically. */
function useRetryWait(retryAt: number | undefined) {
  const [reached, setReached] = useState<number>();
  useEffect(() => {
    if (retryAt === undefined) return;
    const timer = setTimeout(
      () => setReached(retryAt),
      Math.max(0, retryAt - Date.now()),
    );
    return () => clearTimeout(timer);
  }, [retryAt]);
  return {
    waiting: retryAt !== undefined && reached !== retryAt,
    over: retryAt !== undefined && reached === retryAt,
  };
}

/** Whole seconds left before `until` (ms since epoch); `over` once a wait running at mount or later has ended. */
export function useCountdown(until: number | undefined) {
  const [clock, setClock] = useState(() => {
    const now = Date.now();
    return { start: now, now };
  });
  useEffect(() => {
    if (until === undefined) return;
    // Kept running past the deadline: a later deadline then starts from a clock at most a second old.
    const timer = setInterval(
      () => setClock((current) => ({ ...current, now: Date.now() })),
      1000,
    );
    return () => clearInterval(timer);
  }, [until]);
  const left =
    until === undefined
      ? 0
      : Math.max(0, Math.ceil((until - clock.now) / 1000));
  return {
    left,
    over: until !== undefined && until > clock.start && left === 0,
  };
}

/** Approximates the API's identity (case and edge spaces ignored): an incomplete match costs at most one 429. */
export const sameIdentity = (
  sent: string | undefined,
  typed: string | undefined,
) => sent?.trim().toLowerCase() === typed?.trim().toLowerCase();

/** Holds the button from the refusal to the deadline, for the attempts it concerns (`applies`); the API stays the judge. */
export function useRetryHold(error: unknown, applies = true) {
  const retryAt = retryAtOf(error);
  const { waiting, over } = useRetryWait(retryAt);
  const hintId = useId();
  return { retryAt, applies, over, held: waiting && applies, hintId };
}
export type RetryHold = ReturnType<typeof useRetryHold>;

/** Goes in the refusal alert, so the time is announced with it; the held button points to it. */
export function RetryHint({
  hold,
  action = 'réessayer',
  hint = (time) => `Tu pourras ${action} à ${time}.`,
}: {
  hold: RetryHold;
  action?: string;
  hint?: (time: string) => string;
}) {
  if (!hold.held || hold.retryAt === undefined) return null;
  return <p id={hold.hintId}>{hint(timeAfterWait(hold.retryAt))}</p>;
}

/** Mounted with the wait, empty until it ends: a screen reader announces the change, not the insertion. */
export function RetryStatus({ hold }: { hold: RetryHold }) {
  if (hold.retryAt === undefined || !hold.applies) return null;
  return (
    <p role="status" className="sr-only">
      {hold.over ? retryOverMessage : ''}
    </p>
  );
}

/** Not `disabled`, which drops the focus; `aria-disabled` does not stop Enter in a field, so the click is where nothing goes out. */
export function RetryButton({
  hold,
  sending = false,
  waiting = false,
  className,
  onClick,
  ...props
}: ComponentProps<typeof Button> & {
  hold: RetryHold;
  sending?: boolean;
  /** A wait that the label of the button tells. */
  waiting?: boolean;
}) {
  const blocked = hold.held || sending || waiting;
  return (
    <Button
      {...props}
      // No opacity, which would also fade the focus ring: a held main button keeps a pale blue with readable text.
      className={cn(
        'aria-disabled:cursor-not-allowed aria-disabled:text-foreground/65 data-[variant=default]:aria-disabled:bg-primary/25 data-[variant=default]:aria-disabled:text-foreground data-[variant=default]:aria-disabled:hover:bg-primary/25',
        className,
      )}
      aria-disabled={blocked}
      aria-describedby={hold.held ? hold.hintId : undefined}
      onClick={(event) => {
        if (blocked) event.preventDefault();
        else onClick?.(event);
      }}
    />
  );
}
