import type { Ref } from 'react';
import { RetryButton, useCountdown, type RetryHold } from './retry-wait';

/** The resend of an email: a secondary button, held with the seconds left in its label while the API holds the address. */
export function ResendButton({
  what,
  until,
  hold,
  sending,
  sent,
  buttonRef,
  paused = false,
  onResend,
}: {
  /** What is sent again: « le code », « le lien ». */
  what: string;
  /** When the address may get another email (ms since epoch). */
  until: number | undefined;
  hold: RetryHold;
  sending: boolean;
  sent: boolean;
  buttonRef?: Ref<HTMLButtonElement>;
  /** Held without a wait of its own, while another request of the screen runs. */
  paused?: boolean;
  onResend: () => void;
}) {
  const countdown = useCountdown(until);
  return (
    <div className="flex flex-col gap-2 text-center text-sm">
      {/* Mounted empty, out of the flow so it adds no gap: a screen reader announces what fills it, not its insertion. */}
      <div role="status" className="empty:absolute">
        {sending ? <span className="sr-only">Envoi en cours…</span> : null}
        {sent ? <p>Email renvoyé.</p> : null}
      </div>
      <p role="status" className="sr-only">
        {countdown.over ? 'Tu peux renvoyer l’email.' : ''}
      </p>
      <RetryButton
        ref={buttonRef}
        type="button"
        variant="outline"
        className="min-h-11 w-full"
        hold={hold}
        sending={sending}
        waiting={paused || countdown.left > 0}
        onClick={onResend}
      >
        {sending
          ? 'Envoi en cours…'
          : countdown.left > 0
            ? `Renvoyer dans ${countdown.left} s`
            : `Renvoyer ${what}`}
      </RetryButton>
    </div>
  );
}
