import type { Ref } from 'react';
import { Link } from 'react-router';
import {
  codeBlockHint,
  hasCode,
  isCodeBlock,
  recentlySentMessage,
  registrationCodeMessage,
  retryAtOf,
} from '../../errors';
import type { useResendConfirmation } from '../../queries';
import {
  RetryHint,
  RetryStatus,
  type RetryHold,
} from '../../shared/components/retry-wait';
import { ResendButton } from '../../shared/components/resend-button';
import { FormError } from '../../shared/components/form-error';

export function ResendConfirmation({
  mutation,
  registration,
  resendAt,
  hold,
  hintHold,
  statusHold,
  buttonRef,
  paused,
  onSent,
}: {
  /** Lifted to the screen: a refusal of this request can stop the entry of the code too. */
  mutation: ReturnType<typeof useResendConfirmation>;
  registration: string;
  /** When the email of the page that asked for the code allows another one. */
  resendAt: number | undefined;
  hold: RetryHold;
  hintHold: RetryHold;
  /** The wait announced when it ends; absent when the screen announces it elsewhere. */
  statusHold?: RetryHold;
  buttonRef?: Ref<HTMLButtonElement>;
  /** Held while the code is being checked. */
  paused: boolean;
  onSent?: () => void;
}) {
  const recentlySent = hasCode(mutation.error, ['MAIL_RECENTLY_SENT']);
  async function resend() {
    try {
      await mutation.mutateAsync({ registration });
      onSent?.();
    } catch {
      /* The refusal is shown by the state of the mutation. */
    }
  }
  return (
    <div className="flex flex-col gap-4">
      {mutation.isError && !recentlySent ? (
        <FormError className="text-center">
          <p>{registrationCodeMessage(mutation.error)}</p>
          <RetryHint
            hold={hintHold}
            action="demander un nouveau code"
            hint={isCodeBlock(mutation.error) ? codeBlockHint : undefined}
          />
          {hasCode(mutation.error, ['NO_PENDING_REGISTRATION']) ? (
            <p>
              <Link to="/inscription">Recommencer l’inscription</Link>
            </p>
          ) : null}
        </FormError>
      ) : null}
      <div role="status" className="empty:absolute">
        {recentlySent ? (
          <div className="text-center text-sm">
            <p>{recentlySentMessage}</p>
            <RetryHint hold={hintHold} action="demander un nouveau code" />
          </div>
        ) : null}
      </div>
      {statusHold ? <RetryStatus hold={statusHold} /> : null}
      <ResendButton
        what="le code"
        until={
          mutation.isSuccess
            ? mutation.data
            : recentlySent
              ? retryAtOf(mutation.error)
              : resendAt
        }
        hold={hold}
        sending={mutation.isPending}
        sent={mutation.isSuccess}
        buttonRef={buttonRef}
        paused={paused}
        onResend={() => void resend()}
      />
    </div>
  );
}
