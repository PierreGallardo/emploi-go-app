import { useEffect, useRef, useState } from 'react';
import { useLocation } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import { googleMessage } from '../../google-errors';
import { useConfirmGoogleEmail } from '../../queries';
import { GoogleButton } from '../../shared/components/google-button';
import { AuthCard } from '../../shared/components/auth-card';
import { AuthLink, AuthLinks } from '../../shared/components/auth-form';
import { FormError } from '../../shared/components/form-error';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  useRetryHold,
} from '../../shared/components/retry-wait';

export function GoogleConfirmationScreen() {
  const location = useLocation();
  const version = useRef(0);
  const [token, setToken] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const mutation = useConfirmGoogleEmail();
  const [confirmed, setConfirmed] = useState(false);
  // The quota counts the link, nothing that is typed: the button is held until the deadline, or until another link replaces this one.
  const hold = useRetryHold(mutation.error);
  const { reset } = mutation;
  useEffect(() => {
    let active = true;
    const capture = () => {
      if (!active) return;
      setReady(true);
      const fragment = window.location.hash.slice(1);
      if (!fragment) return;
      window.history.replaceState(
        window.history.state,
        '',
        window.location.pathname,
      );
      version.current += 1;
      setToken(
        /^[0-9a-f-]{36}\.[A-Za-z0-9_-]{43}$/i.test(fragment) ? fragment : null,
      );
      setConfirmed(false);
      reset();
    };
    queueMicrotask(capture);
    window.addEventListener('hashchange', capture);
    return () => {
      active = false;
      window.removeEventListener('hashchange', capture);
    };
  }, [location.hash, location.key, reset]);
  return (
    <AuthCard
      title={confirmed ? 'Adresse confirmée' : 'Confirmer ton email'}
      description={
        confirmed
          ? undefined
          : 'Confirme uniquement si tu as demandé ce lien avec Google.'
      }
    >
      <LoadingStatus loading={!ready} silent={!token} />
      {!ready ? (
        <p className="text-center text-sm">Lecture du lien…</p>
      ) : confirmed ? (
        <>
          <p role="status" className="text-center text-sm">
            Ton email est confirmé. Continue avec Google pour accéder à ton
            compte.
          </p>
          <GoogleButton />
        </>
      ) : (
        <>
          {ready && !token ? (
            <FormError className="text-center">
              Ce lien est invalide. Reviens à Google pour demander un nouveau
              lien.
            </FormError>
          ) : null}
          {mutation.isError ? (
            <FormError className="text-center">
              <p>{googleMessage(mutation.error)}</p>
              <RetryHint hold={hold} />
            </FormError>
          ) : null}
          <RetryStatus hold={hold} />
          <RetryButton
            className="min-h-11 w-full"
            disabled={!ready || !token}
            hold={hold}
            sending={mutation.isPending}
            onClick={async () => {
              if (!token) return;
              const current = version.current;
              try {
                await mutation.mutateAsync({ token });
                if (current === version.current) {
                  setToken(null);
                  setConfirmed(true);
                  mutation.reset();
                }
              } catch {
                /* Controlled error, without logging the bearer. */
              }
            }}
          >
            {mutation.isPending ? 'Confirmation…' : 'Confirmer mon email'}
          </RetryButton>
        </>
      )}
      <AuthLinks>
        <AuthLink to="/connexion-google">Revenir à Google</AuthLink>
      </AuthLinks>
    </AuthCard>
  );
}
