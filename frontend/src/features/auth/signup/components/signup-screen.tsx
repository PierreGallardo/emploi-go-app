import { useEffect, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import { Navigate, useLocation, useNavigate } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import {
  SessionUnconfirmedError,
  authenticationMessage,
  hasCode,
  signupMessage,
} from '../../errors';
import { readSignupFormState } from '../registration-code-state';
import {
  sessionQueryOptions,
  useSessionCheckFailure,
  useSignup,
} from '../../queries';
import {
  signupSchema,
  type SignupOutput,
  type SignupValues,
} from '../../schemas';
import { AuthInput } from '../../shared/components/auth-input';
import {
  AuthFields,
  AuthForm,
  AuthLink,
  AuthLinks,
  OrSeparator,
} from '../../shared/components/auth-form';
import { AuthCard } from '../../shared/components/auth-card';
import { SignupFormSkeleton } from '../../shared/components/auth-form-skeleton';
import { GoogleButton } from '../../shared/components/google-button';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  sameIdentity,
  useRetryHold,
} from '../../shared/components/retry-wait';
import { SessionRetryButton } from '../../shared/components/session-check-error';
import { RememberMeCheckbox } from '../../shared/components/remember-me-checkbox';
import { TermsCheckbox } from '../../shared/components/terms-checkbox';
import { FormError } from '../../shared/components/form-error';

const errorId = 'signup-error';

export function SignupScreen() {
  const navigate = useNavigate();
  const location = useLocation();
  const mutation = useSignup();
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
  const form = useForm<SignupValues, unknown, SignupOutput>({
    resolver: zodResolver(signupSchema),
    defaultValues: {
      email: '',
      rememberMe: false,
      ...readSignupFormState(location.state),
      password: '',
      confirmation: '',
      acceptedTerms: false,
    },
  });
  // Only a confirmed session hides the form; a failed check keeps its error and button in place of the skeleton.
  const failed = !!failure || session.isError;
  // The wait of the email that was sent: another address is not held (the mail quota counts the address).
  const email = useWatch({ control: form.control, name: 'email' });
  const password = useWatch({ control: form.control, name: 'password' });
  const hold = useRetryHold(
    mutation.error,
    sameIdentity(mutation.variables?.email, email),
  );
  const { setFocus } = form;
  const refusal = mutation.error;
  const refused = mutation.variables;
  const weakPassword =
    hasCode(refusal, ['WEAK_PASSWORD']) && refused?.password === password;
  useEffect(() => {
    if (hasCode(refusal, ['WEAK_PASSWORD']))
      setFocus('password', { shouldSelect: true });
  }, [refusal, setFocus]);
  const sending = mutation.isPending || form.formState.isSubmitting;
  const checking =
    (session.isPending && !failure) || (session.isFetching && !!session.data);
  if (checking && !mutation.isPending)
    return (
      <AuthCard
        title="Créer un compte"
        description="Découvre les entreprises et les métiers près de chez toi."
        step={1}
        placeholder
      >
        <LoadingStatus loading />
        <SignupFormSkeleton />
      </AuthCard>
    );
  // A session the API refused and could not restore is not confirmed: the visitor needs this form to sign in again.
  if (session.data && !(session.error instanceof SessionUnconfirmedError))
    return <Navigate to="/" replace />;
  return (
    <AuthCard
      title="Créer un compte"
      description="Découvre les entreprises et les métiers près de chez toi."
      step={1}
    >
      <LoadingStatus loading={false} silent={failed} />
      {failed ? (
        <div className="flex flex-col gap-4">
          <FormError>{authenticationMessage(checkError)}</FormError>
          <SessionRetryButton
            error={checkError}
            retrying={session.fetchStatus !== 'idle'}
            onRetry={() => void session.refetch()}
          />
        </div>
      ) : null}
      <AuthForm
        onSubmit={(event) =>
          void form.handleSubmit(async (values) => {
            let accepted;
            try {
              accepted = await mutation.mutateAsync(values);
            } catch {
              // The refusal is shown by the state of the mutation, with the wait the API asked for.
              return;
            }
            // The form's own entry keeps the email and the box, never the password: the back button returns to it filled, as « Modifier » does.
            const kept = { email: values.email, rememberMe: values.rememberMe };
            await navigate('/inscription', { replace: true, state: kept });
            void navigate('/inscription/code', {
              state: { ...kept, ...accepted },
            });
          })(event)
        }
      >
        <AuthFields>
          <AuthInput
            name="signup-email"
            label="Email"
            type="email"
            autoComplete="email"
            registration={form.register('email')}
            error={form.formState.errors.email?.message}
          />
          <AuthInput
            name="signup-password"
            label="Mot de passe"
            type="password"
            autoComplete="new-password"
            checkedPassword={password}
            shown={passwordShown}
            onToggleShown={() => setPasswordShown((shown) => !shown)}
            registration={form.register('password')}
            error={form.formState.errors.password?.message}
            invalid={weakPassword}
            describedBy={errorId}
          />
          <AuthInput
            name="signup-confirmation"
            label="Confirmer le mot de passe"
            type="password"
            autoComplete="new-password"
            shown={passwordShown}
            registration={form.register('confirmation')}
            error={form.formState.errors.confirmation?.message}
          />
          <TermsCheckbox
            name="signup-terms"
            registration={form.register('acceptedTerms')}
            error={form.formState.errors.acceptedTerms?.message}
          />
          <RememberMeCheckbox
            id="signup-remember-me"
            {...form.register('rememberMe')}
          />
        </AuthFields>
        {mutation.isError ? (
          <FormError id={errorId}>
            <p>{signupMessage(mutation.error)}</p>
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
          {mutation.isPending ? 'Création…' : 'Créer mon compte'}
        </RetryButton>
      </AuthForm>
      <OrSeparator />
      <GoogleButton disabled={changingSession} />
      <AuthLinks>
        <AuthLink to="/connexion">Déjà un compte ? Se connecter</AuthLink>
      </AuthLinks>
    </AuthCard>
  );
}
