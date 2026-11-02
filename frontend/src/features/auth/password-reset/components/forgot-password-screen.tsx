import { useState } from 'react';
import { cn } from 'cn';
import { useForm, useWatch } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { hasCode, recoveryMessage, retryAtOf } from '../../errors';
import { useRequestRecovery } from '../../queries';
import { resendSchema } from '../../schemas';
import { AuthInput } from '../../shared/components/auth-input';
import {
  AuthFields,
  AuthForm,
  AuthLink,
  AuthLinks,
  authLinkClass,
} from '../../shared/components/auth-form';
import { AuthCard } from '../../shared/components/auth-card';
import { FormError } from '../../shared/components/form-error';
import { ResendButton } from '../../shared/components/resend-button';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  sameIdentity,
  useRetryHold,
} from '../../shared/components/retry-wait';

export function ForgotPasswordScreen() {
  const mutation = useRequestRecovery();
  // The address of an accepted request: the form gives way to its confirmation.
  const [sent, setSent] = useState<{
    email: string;
    resendAt?: number;
    resent: boolean;
  }>();
  const form = useForm<{ email: string }>({
    resolver: zodResolver(resendSchema),
    defaultValues: { email: '' },
  });
  // The wait of the email that was sent: another address is not held (the mail quota counts the address).
  const typed = useWatch({ control: form.control, name: 'email' });
  const hold = useRetryHold(
    mutation.error,
    sameIdentity(mutation.variables?.email, sent?.email ?? typed),
  );
  // An email was asked less than a minute ago for this address: the visitor gets the answer of an accepted request.
  const recentlySent = hasCode(mutation.error, ['MAIL_RECENTLY_SENT']);
  async function request(email: string, again: boolean) {
    // « Email renvoyé. » speaks of the last request only.
    if (again) setSent({ email, resent: false });
    try {
      const resendAt = await mutation.mutateAsync({ email });
      setSent({ email, resendAt, resent: again });
      // The wait of a refused email stays on the button: only an accepted request clears its error.
      mutation.reset();
    } catch (error) {
      if (hasCode(error, ['MAIL_RECENTLY_SENT']))
        setSent({ email, resendAt: retryAtOf(error), resent: false });
    }
  }
  if (sent)
    return (
      <AuthCard
        title="Vérifie tes emails"
        description={
          <>
            Si un compte correspond à cette adresse, un message sera envoyé.
            Vérifie aussi les courriers indésirables. Le lien est valable 30
            minutes.
            <span className="mt-2 block">
              {sent.email} ·{' '}
              <button
                type="button"
                aria-label="Modifier l’adresse email"
                className={cn(
                  authLinkClass,
                  'font-medium text-primary after:-inset-y-3.5',
                )}
                // The error keeps the wait the API asked for: the form stays held until then.
                onClick={() => setSent(undefined)}
              >
                Modifier
              </button>
            </span>
          </>
        }
      >
        {mutation.isError && !recentlySent ? (
          <FormError className="text-center">
            <p>{recoveryMessage(mutation.error)}</p>
            <RetryHint hold={hold} />
          </FormError>
        ) : null}
        <RetryStatus hold={hold} />
        <ResendButton
          what="le lien"
          until={sent.resendAt}
          hold={hold}
          sending={mutation.isPending}
          sent={sent.resent}
          onResend={() => void request(sent.email, true)}
        />
        <AuthLinks>
          <AuthLink to="/connexion">Revenir à la connexion</AuthLink>
        </AuthLinks>
      </AuthCard>
    );
  return (
    <AuthCard
      title="Mot de passe oublié"
      description="Indique ton email pour recevoir un lien de récupération."
    >
      <AuthForm
        onSubmit={(event) =>
          void form.handleSubmit(({ email }) => request(email, false))(event)
        }
      >
        <AuthFields>
          <AuthInput
            name="recovery-email"
            label="Email"
            type="email"
            autoComplete="email"
            registration={form.register('email')}
            error={form.formState.errors.email?.message}
          />
        </AuthFields>
        {mutation.isError && !recentlySent ? (
          <FormError>
            <p>{recoveryMessage(mutation.error)}</p>
            <RetryHint hold={hold} />
          </FormError>
        ) : null}
        <RetryStatus hold={hold} />
        <RetryButton
          className="min-h-11 w-full"
          hold={hold}
          sending={mutation.isPending || form.formState.isSubmitting}
        >
          {mutation.isPending ? 'Demande en cours…' : 'Envoyer la demande'}
        </RetryButton>
      </AuthForm>
      <AuthLinks>
        <AuthLink to="/connexion">Revenir à la connexion</AuthLink>
      </AuthLinks>
    </AuthCard>
  );
}
