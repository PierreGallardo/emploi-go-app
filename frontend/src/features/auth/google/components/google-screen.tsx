import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { Navigate } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import { hasCode } from '../../errors';
import { googleCodeMessage, googleMessage } from '../../google-errors';
import {
  googleStatusQueryOptions,
  useGoogleAccess,
  useGoogleSignup,
  useGoogleLink,
  useRequestGoogleEmail,
} from '../../queries';
import { credentialsSchema, type CredentialsValues } from '../../schemas';
import { AuthInput } from '../../shared/components/auth-input';
import {
  AuthFields,
  AuthForm,
  AuthLink,
  AuthLinks,
} from '../../shared/components/auth-form';
import { FormError } from '../../shared/components/form-error';
import { AuthCard } from '../../shared/components/auth-card';
import { GoogleButton } from '../../shared/components/google-button';
import { UsernameChoice } from '../../shared/components/username-choice';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  sameIdentity,
  useRetryHold,
} from '../../shared/components/retry-wait';

function GoogleBackLink() {
  return <AuthLink to="/connexion">Revenir à la connexion</AuthLink>;
}

function GoogleUsername({ flowId }: { flowId: string }) {
  const mutation = useGoogleSignup();
  if (mutation.isSuccess) return <Navigate to="/" replace />;
  return (
    <UsernameChoice
      withTerms
      error={mutation.error}
      message={googleMessage(mutation.error)}
      sending={mutation.isPending}
      onChoose={(values) => mutation.mutateAsync({ ...values, flowId })}
      footer={
        <AuthLinks>
          <GoogleBackLink />
        </AuthLinks>
      }
    />
  );
}
function GoogleAccess({ flowId }: { flowId: string }) {
  const mutation = useGoogleAccess();
  const [passwordShown, setPasswordShown] = useState(false);
  const form = useForm<CredentialsValues>({
    resolver: zodResolver(credentialsSchema),
    defaultValues: { identifier: '', password: '' },
  });
  // The same quota as the login: it counts the identifier that was sent, and another one is not held.
  const identifier = useWatch({ control: form.control, name: 'identifier' });
  const hold = useRetryHold(
    mutation.error,
    sameIdentity(mutation.variables?.identifier, identifier),
  );
  return (
    <AuthCard
      title="Retrouve ton compte"
      description="Connecte-toi au compte qui utilise le même email pour y associer Google."
    >
      <AuthForm
        onSubmit={form.handleSubmit(async (values) => {
          try {
            await mutation.mutateAsync({ ...values, flowId });
            form.resetField('password');
          } catch {
            /* Controlled error below. */
          }
        })}
      >
        <AuthFields>
          <AuthInput
            name="google-identifier"
            label="Email ou pseudo"
            autoComplete="username"
            registration={form.register('identifier')}
            error={form.formState.errors.identifier?.message}
          />
          <AuthInput
            name="google-password"
            label="Mot de passe"
            type="password"
            autoComplete="current-password"
            shown={passwordShown}
            onToggleShown={() => setPasswordShown((shown) => !shown)}
            registration={form.register('password')}
            error={form.formState.errors.password?.message}
          />
        </AuthFields>
        {mutation.isError ? (
          <FormError>
            <p>{googleMessage(mutation.error)}</p>
            <RetryHint hold={hold} />
          </FormError>
        ) : null}
        <RetryStatus hold={hold} />
        <RetryButton
          type="submit"
          className="min-h-11 w-full"
          hold={hold}
          sending={mutation.isPending || form.formState.isSubmitting}
        >
          {mutation.isPending ? 'Vérification…' : 'Vérifier mon compte'}
        </RetryButton>
      </AuthForm>
      <AuthLinks>
        <AuthLink to="/mot-de-passe-oublie">Mot de passe oublié ?</AuthLink>
        <GoogleBackLink />
      </AuthLinks>
    </AuthCard>
  );
}
function GoogleEmail({ flowId }: { flowId: string }) {
  const mutation = useRequestGoogleEmail();
  // The quota counts the flow: whatever happened on the screen, the button is held until the deadline.
  const hold = useRetryHold(mutation.error);
  // An email asked less than a minute ago has been sent: the screen reports it as an accepted request.
  const recentlySent = hasCode(mutation.error, ['MAIL_RECENTLY_SENT']);
  const sent = mutation.isSuccess || recentlySent;
  return (
    <AuthCard
      title="Confirme ton email"
      description="Reçois un lien par email, puis reviens continuer avec Google."
    >
      <div role="status" className="empty:absolute">
        {sent ? (
          <div className="text-center text-sm">
            <p>
              Consulte tes emails et les courriers indésirables. Le lien est
              valable 24 heures. Utilise le dernier lien reçu.
            </p>
            {recentlySent ? <RetryHint hold={hold} /> : null}
          </div>
        ) : null}
      </div>
      {mutation.isError && !recentlySent ? (
        <FormError>
          <p>{googleMessage(mutation.error)}</p>
          <RetryHint hold={hold} />
        </FormError>
      ) : null}
      <p className="text-center text-sm text-muted-foreground">
        Attends une minute entre deux demandes.
      </p>
      <RetryStatus hold={hold} />
      <RetryButton
        // Once sent, waiting for the email is what comes next: the resend steps back to a secondary action.
        variant={sent ? 'outline' : 'default'}
        className="min-h-11 w-full"
        hold={hold}
        sending={mutation.isPending}
        onClick={() => mutation.mutate({ flowId })}
      >
        {mutation.isPending
          ? 'Envoi en cours…'
          : sent
            ? 'Renvoyer le lien'
            : 'Envoyer le lien'}
      </RetryButton>
      <GoogleButton disabled={mutation.isPending} />
      <AuthLinks>
        <GoogleBackLink />
      </AuthLinks>
    </AuthCard>
  );
}
function GoogleLink({ flowId }: { flowId: string }) {
  const mutation = useGoogleLink();
  // The link counts the flow: the button waits for its deadline.
  const hold = useRetryHold(mutation.error);
  if (mutation.data?.changed) return <Navigate to="/" replace />;
  return (
    <AuthCard
      title="Associer Google à ton compte"
      description="Tu pourras te connecter avec Google ou ton mot de passe. Ton compte reste le même."
    >
      <p className="text-center text-sm text-muted-foreground">
        Tu recevras un email de confirmation.
      </p>
      {mutation.isError ? (
        <FormError>
          <p>{googleMessage(mutation.error)}</p>
          <RetryHint hold={hold} />
        </FormError>
      ) : null}
      <RetryStatus hold={hold} />
      <RetryButton
        className="min-h-11 w-full"
        hold={hold}
        sending={mutation.isPending}
        onClick={() => mutation.mutate({ confirmed: true, flowId })}
      >
        {mutation.isPending
          ? 'Association en cours…'
          : 'Confirmer l’association de Google'}
      </RetryButton>
      <AuthLinks>
        <AuthLink to="/">Annuler</AuthLink>
        <GoogleBackLink />
      </AuthLinks>
    </AuthCard>
  );
}
export function GoogleScreen() {
  const changing = useIsMutating({ mutationKey: ['auth'] }) > 0;
  const status = useQuery({
    ...googleStatusQueryOptions(),
    enabled: !changing,
  });
  // Keep the stage mounted through its mutation so the result stays visible.
  const stage = status.data?.stage;
  const flowId = status.data?.flowId;
  return (
    <>
      <LoadingStatus
        loading={status.isPending}
        silent={status.isError || !!status.data?.errorCode}
      />
      {stage === 'CHOOSE_USERNAME' && flowId ? (
        <GoogleUsername flowId={flowId} key={flowId} />
      ) : stage === 'ACCOUNT_ACCESS_REQUIRED' && flowId ? (
        <GoogleAccess flowId={flowId} key={flowId} />
      ) : stage === 'EMAIL_CONFIRMATION_REQUIRED' && flowId ? (
        <GoogleEmail flowId={flowId} key={flowId} />
      ) : stage === 'CONFIRM_LINK' && flowId ? (
        <GoogleLink flowId={flowId} key={flowId} />
      ) : status.isPending ? (
        <AuthCard title="Continuer avec Google" placeholder>
          <p className="text-center text-sm">Vérification de Google…</p>
        </AuthCard>
      ) : (
        <AuthCard
          title="Continuer avec Google"
          description="Connecte-toi ou crée ton compte avec Google."
        >
          {status.isError || status.data?.errorCode ? (
            <FormError className="text-center">
              {status.isError
                ? googleMessage(status.error)
                : googleCodeMessage(status.data?.errorCode ?? undefined)}
            </FormError>
          ) : null}
          <GoogleButton disabled={changing} />
          <AuthLinks>
            <GoogleBackLink />
          </AuthLinks>
        </AuthCard>
      )}
    </>
  );
}
