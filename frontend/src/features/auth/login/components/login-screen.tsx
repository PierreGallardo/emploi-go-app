import { useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import { SessionUnconfirmedError, authenticationMessage } from '../../errors';
import { loginSchema, type LoginValues } from '../../schemas';
import {
  sessionQueryOptions,
  useLogin,
  useSessionCheckFailure,
} from '../../queries';
import { GoogleButton } from '../../shared/components/google-button';
import { AuthInput } from '../../shared/components/auth-input';
import {
  AuthFields,
  AuthForm,
  AuthLink,
  AuthLinks,
  OrSeparator,
} from '../../shared/components/auth-form';
import { RememberMeCheckbox } from '../../shared/components/remember-me-checkbox';
import { AuthCard } from '../../shared/components/auth-card';
import { LoginFormSkeleton } from '../../shared/components/auth-form-skeleton';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  sameIdentity,
  useRetryHold,
} from '../../shared/components/retry-wait';
import { SessionRetryButton } from '../../shared/components/session-check-error';
import { FormError } from '../../shared/components/form-error';

export function LoginScreen() {
  const mutation = useLogin();
  const [passwordShown, setPasswordShown] = useState(false);
  const changingSession = useIsMutating({ mutationKey: ['auth'] }) > 0;
  const session = useQuery({
    ...sessionQueryOptions(),
    // A session kept in the cache may be gone (a protected read just got a 401): ask again on arrival.
    refetchOnMount: 'always',
    enabled: !changingSession,
  });
  const failure = useSessionCheckFailure(session);
  const checkError = failure?.error ?? session.error;
  const form = useForm<LoginValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { identifier: '', password: '', rememberMe: false },
  });
  // The form shows unless the session is confirmed: a failed check keeps its error and « Réessayer » in place of the skeleton.
  const failed = !!failure || session.isError;
  // Only the identifier sent is held: the API stays the judge, and an incomplete comparison costs one 429 at most.
  const identifier = useWatch({ control: form.control, name: 'identifier' });
  const hold = useRetryHold(
    mutation.error,
    sameIdentity(mutation.variables?.identifier, identifier),
  );
  const sending = mutation.isPending || form.formState.isSubmitting;
  const submit = form.handleSubmit(async (values) => {
    try {
      await mutation.mutateAsync(values);
      form.resetField('password');
    } catch {
      /* The refusal is shown by the state of the mutation. */
    }
  });
  const checking =
    (session.isPending && !failure) || (session.isFetching && !!session.data);
  if (mutation.isSuccess) return <Navigate to="/" replace />;
  if (checking && !mutation.isPending)
    return (
      <AuthCard
        title="Se connecter"
        description="Retrouve les entreprises et les métiers près de toi."
        placeholder
      >
        <LoadingStatus loading />
        <LoginFormSkeleton />
      </AuthCard>
    );
  // A session the API refused and could not restore is not confirmed: the visitor needs this form to sign in again.
  if (session.data && !(session.error instanceof SessionUnconfirmedError))
    return <Navigate to="/" replace />;
  return (
    <AuthCard
      title="Se connecter"
      description="Retrouve les entreprises et les métiers près de toi."
    >
      <LoadingStatus loading={false} silent={failed} />
      {failed ? (
        <div className="flex flex-col gap-4">
          <FormError>
            <p>{authenticationMessage(checkError)}</p>
          </FormError>
          <SessionRetryButton
            error={checkError}
            retrying={session.fetchStatus !== 'idle'}
            onRetry={() => void session.refetch()}
          />
        </div>
      ) : null}
      <AuthForm onSubmit={(event) => void submit(event)}>
        <AuthFields>
          <AuthInput
            name="identifier"
            label="Email ou pseudo"
            autoComplete="username"
            maxLength={320}
            registration={form.register('identifier')}
            error={form.formState.errors.identifier?.message}
          />
          <AuthInput
            name="password"
            label="Mot de passe"
            type="password"
            autoComplete="current-password"
            shown={passwordShown}
            onToggleShown={() => setPasswordShown((shown) => !shown)}
            registration={form.register('password')}
            error={form.formState.errors.password?.message}
          />
          <RememberMeCheckbox
            id="login-remember-me"
            {...form.register('rememberMe')}
          />
        </AuthFields>
        {mutation.isError ? (
          <FormError>
            <p>{authenticationMessage(mutation.error)}</p>
            <RetryHint hold={hold} />
          </FormError>
        ) : null}
        <RetryStatus hold={hold} />
        <RetryButton
          type="submit"
          className="min-h-11 w-full"
          hold={hold}
          sending={sending}
        >
          {mutation.isPending ? 'Connexion en cours…' : 'Se connecter'}
        </RetryButton>
      </AuthForm>
      <OrSeparator />
      <GoogleButton disabled={changingSession} />
      <AuthLinks>
        <AuthLink to="/mot-de-passe-oublie">Mot de passe oublié ?</AuthLink>
        <AuthLink to="/inscription">Pas encore de compte ? S’inscrire</AuthLink>
      </AuthLinks>
    </AuthCard>
  );
}
