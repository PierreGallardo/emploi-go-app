import { useEffect, useRef, useState } from 'react';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { Link, useLocation } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import { Button } from '@/components/ui/button';
import { AuthenticationError, recoveryMessage } from '../../errors';
import { useResetPassword } from '../../queries';
import { resetPasswordSchema, type ResetPasswordValues } from '../../schemas';
import { AuthInput } from '../../shared/components/auth-input';
import {
  AuthFields,
  AuthForm,
  AuthLink,
  AuthLinks,
} from '../../shared/components/auth-form';
import { AuthCard } from '../../shared/components/auth-card';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  useRetryHold,
} from '../../shared/components/retry-wait';
import { FormError } from '../../shared/components/form-error';

export function ResetPasswordScreen() {
  const location = useLocation();
  const version = useRef(0);
  const [token, setToken] = useState<string | null>(null);
  const [ready, setReady] = useState(false);
  const [changed, setChanged] = useState(false);
  const [passwordShown, setPasswordShown] = useState(false);
  const mutation = useResetPassword();
  const form = useForm<ResetPasswordValues>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { password: '', confirmation: '' },
  });
  const password = useWatch({ control: form.control, name: 'password' });
  // Both quotas count the link or the account, never what is typed: the button stays held until its deadline.
  const hold = useRetryHold(mutation.error);
  const { reset: resetForm } = form;
  const { reset: resetMutation } = mutation;
  useEffect(() => {
    let active = true;
    function capture() {
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
        /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43}$/i.test(
          fragment,
        )
          ? fragment
          : null,
      );
      setChanged(false);
      resetForm();
      resetMutation();
    }
    queueMicrotask(capture);
    window.addEventListener('hashchange', capture);
    return () => {
      active = false;
      version.current += 1;
      window.removeEventListener('hashchange', capture);
    };
  }, [location.hash, location.key, resetForm, resetMutation]);
  const unusable =
    mutation.error instanceof AuthenticationError &&
    ['INVALID_LINK', 'EXPIRED_LINK', 'USED_LINK', 'REPLACED_LINK'].includes(
      mutation.error.code ?? '',
    );
  async function submit(values: ResetPasswordValues) {
    if (!token) return;
    const submittedVersion = version.current;
    try {
      await mutation.mutateAsync({ ...values, token });
      if (version.current !== submittedVersion) return;
      setChanged(true);
      setToken(null);
      form.reset();
      mutation.reset();
    } catch {
      /* The refusal is shown by the state of the mutation. */
    }
  }
  return (
    <AuthCard
      title={changed ? 'Mot de passe changé' : 'Nouveau mot de passe'}
      description={
        changed
          ? undefined
          : 'Choisis un mot de passe pour retrouver ton compte.'
      }
    >
      <LoadingStatus loading={!ready} silent={!token} />
      {!ready ? (
        <p>Lecture du lien…</p>
      ) : changed ? (
        <>
          <p role="status" className="text-center text-sm">
            Ton mot de passe a été changé. Connecte-toi avec ton nouveau mot de
            passe sur tes appareils.
          </p>
          <Button asChild className="min-h-11 w-full">
            <Link to="/connexion">Se connecter</Link>
          </Button>
        </>
      ) : (
        <>
          {!token ? (
            <FormError className="text-center">
              Ce lien est invalide. Ouvre le dernier lien reçu ou demande un
              nouveau lien.
            </FormError>
          ) : null}
          {mutation.isError ? (
            <FormError className="text-center">
              <p>{recoveryMessage(mutation.error)}</p>
              <RetryHint hold={hold} />
            </FormError>
          ) : null}
          {token && !unusable ? (
            <AuthForm
              onSubmit={(event) => void form.handleSubmit(submit)(event)}
            >
              <AuthFields>
                <AuthInput
                  name="reset-password"
                  label="Nouveau mot de passe"
                  type="password"
                  autoComplete="new-password"
                  checkedPassword={password}
                  shown={passwordShown}
                  onToggleShown={() => setPasswordShown((shown) => !shown)}
                  registration={form.register('password')}
                  error={form.formState.errors.password?.message}
                />
                <AuthInput
                  name="reset-confirmation"
                  label="Confirmer le nouveau mot de passe"
                  type="password"
                  autoComplete="new-password"
                  shown={passwordShown}
                  registration={form.register('confirmation')}
                  error={form.formState.errors.confirmation?.message}
                />
              </AuthFields>
              <RetryStatus hold={hold} />
              <RetryButton
                className="min-h-11 w-full"
                hold={hold}
                sending={mutation.isPending || form.formState.isSubmitting}
              >
                {mutation.isPending
                  ? 'Changement en cours…'
                  : 'Changer mon mot de passe'}
              </RetryButton>
            </AuthForm>
          ) : (
            <Button asChild className="min-h-11 w-full">
              <Link to="/mot-de-passe-oublie">Demander un nouveau lien</Link>
            </Button>
          )}
        </>
      )}
      {changed ? null : (
        <AuthLinks>
          <AuthLink to="/connexion">Se connecter</AuthLink>
        </AuthLinks>
      )}
    </AuthCard>
  );
}
