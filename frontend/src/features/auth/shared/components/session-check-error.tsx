import { Link } from 'react-router';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { authenticationMessage, isReconnectionLimited } from '../../errors';
import { FormError } from './form-error';
import {
  RetryButton,
  RetryHint,
  retryOverMessage,
  useRetryHold,
} from './retry-wait';

/** « Réessayer » after a failed check: while waiting or checking, it stays focusable (`aria-disabled`) and does nothing. */
export function SessionRetryButton({
  error,
  retrying,
  onRetry,
}: {
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
}) {
  const hold = useRetryHold(error);
  return (
    <div className="flex flex-col gap-3 text-sm">
      <RetryHint hold={hold} />
      {/* Always mounted, so that a screen reader announces its changes: a click is never silent. */}
      <p role="status" className="sr-only">
        {retrying
          ? 'Vérification en cours…'
          : hold.over
            ? retryOverMessage
            : ''}
      </p>
      <RetryButton
        type="button"
        variant="outline"
        className="min-h-11 w-full"
        hold={hold}
        sending={retrying}
        onClick={onRetry}
      >
        Réessayer
      </RetryButton>
    </div>
  );
}

export function SessionCheckError({
  error,
  retrying,
  onRetry,
  variant,
}: {
  error: unknown;
  retrying: boolean;
  onRetry: () => void;
  variant: 'default' | 'destructive';
}) {
  const content = (
    <>
      <p>{authenticationMessage(error)}</p>
      {isReconnectionLimited(error) ? (
        <p>
          Ta connexion automatique est limitée pour le moment.{' '}
          <Link to="/connexion">Connecte-toi avec ton mot de passe</Link> ou
          réessaie plus tard.
        </p>
      ) : null}
    </>
  );
  return (
    <>
      {variant === 'destructive' ? (
        <FormError>{content}</FormError>
      ) : (
        <Alert className="[&_a]:font-medium [&_a]:underline [&_a]:underline-offset-4">
          <AlertDescription>{content}</AlertDescription>
        </Alert>
      )}
      <SessionRetryButton error={error} retrying={retrying} onRetry={onRetry} />
    </>
  );
}
