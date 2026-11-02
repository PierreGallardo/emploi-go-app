import { useEffect, useState, type ReactNode } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { hasCode } from '../../errors';
import { googleSignupSchema, type GoogleSignupValues } from '../../schemas';
import { AuthCard, type Step } from './auth-card';
import { AuthFields, AuthForm } from './auth-form';
import { AuthInput } from './auth-input';
import { FormError } from './form-error';
import {
  RetryButton,
  RetryHint,
  RetryStatus,
  useRetryHold,
} from './retry-wait';
import { TermsCheckbox } from './terms-checkbox';

const errorId = 'username-refusal';
const usernameRefusals = ['INVALID_USERNAME', 'USERNAME_UNAVAILABLE'] as const;

/** The screen « Choisis ton pseudo », shared by the local registration and Google. */
export function UsernameChoice({
  step,
  withTerms,
  error,
  message,
  sending,
  onChoose,
  footer,
}: {
  /** The step of the local registration, which accepted the terms at its first step. */
  step?: Step;
  withTerms: boolean;
  error: unknown;
  message: string;
  sending: boolean;
  onChoose: (values: GoogleSignupValues) => Promise<unknown>;
  footer?: ReactNode;
}) {
  const form = useForm<GoogleSignupValues>({
    resolver: zodResolver(googleSignupSchema),
    defaultValues: {
      username: '',
      acceptedTerms: !withTerms,
    },
  });
  // The quota of Google counts the flow, not the pseudo: whatever is typed, the button is held until the deadline.
  const hold = useRetryHold(error);
  const { setFocus } = form;
  const typed = useWatch({ control: form.control, name: 'username' });
  const [sent, setSent] = useState<string>();
  // The refusal speaks of the pseudo that was sent: it stops describing the field once the field changes.
  const refused = hasCode(error, usernameRefusals) && sent === typed.trim();
  useEffect(() => {
    if (hasCode(error, usernameRefusals))
      setFocus('username', { shouldSelect: true });
  }, [error, setFocus]);
  return (
    <AuthCard
      title="Choisis ton pseudo"
      // A Google account has no password: its username shows, but does not sign in.
      description={
        withTerms
          ? 'Il s’affiche sur ton profil.'
          : 'Il s’affiche sur ton profil. Tu peux aussi te connecter avec.'
      }
      step={step}
    >
      <AuthForm
        onSubmit={form.handleSubmit(async (values) => {
          setSent(values.username);
          try {
            await onChoose(values);
          } catch {
            /* The refusal is shown below, and the pseudo stays in the field. */
          }
        })}
      >
        <AuthFields>
          <AuthInput
            name="username"
            label="Pseudo"
            autoComplete="username"
            registration={form.register('username')}
            error={form.formState.errors.username?.message}
            invalid={refused}
            describedBy={errorId}
          />
          {withTerms ? (
            <TermsCheckbox
              name="username-terms"
              registration={form.register('acceptedTerms')}
              error={form.formState.errors.acceptedTerms?.message}
            />
          ) : null}
        </AuthFields>
        {error ? (
          <FormError id={errorId}>
            <p>{message}</p>
            <RetryHint hold={hold} />
          </FormError>
        ) : null}
        <RetryStatus hold={hold} />
        <RetryButton
          type="submit"
          className="min-h-11 w-full"
          hold={hold}
          sending={sending || form.formState.isSubmitting}
        >
          {sending ? 'Enregistrement…' : 'Continuer'}
        </RetryButton>
      </AuthForm>
      {footer}
    </AuthCard>
  );
}
