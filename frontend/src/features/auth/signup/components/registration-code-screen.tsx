import { useEffect, useRef, type FormEvent } from 'react';
import { cn } from 'cn';
import { zodResolver } from '@hookform/resolvers/zod';
import { useController, useForm } from 'react-hook-form';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import { Link, Navigate, useLocation } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import {
  SessionUnconfirmedError,
  authenticationMessage,
  codeBlockHint,
  hasCode,
  isCodeBlock,
  registrationCodeMessage,
  type ErrorCode,
} from '../../errors';
import { readRegistrationCodeState } from '../registration-code-state';
import {
  sessionQueryOptions,
  useConfirmRegistration,
  useResendConfirmation,
  useSessionCheckFailure,
} from '../../queries';
import {
  registrationCodeSchema,
  type RegistrationCodeValues,
} from '../../schemas';
import { AuthCard } from '../../shared/components/auth-card';
import { RegistrationCodeFormSkeleton } from '../../shared/components/auth-form-skeleton';
import { AuthForm, authLinkClass } from '../../shared/components/auth-form';
import { CodeInput } from './code-input';
import { ResendConfirmation } from './resend-confirmation';
import {
  RetryHint,
  RetryStatus,
  useRetryHold,
} from '../../shared/components/retry-wait';
import { SessionRetryButton } from '../../shared/components/session-check-error';
import { FormError } from '../../shared/components/form-error';

const title = 'Vérifie tes emails';
const errorId = 'registration-code-error';
const formatErrorId = 'registration-code-format';
const codeRefusals: readonly ErrorCode[] = [
  'INVALID_CODE',
  'CODE_EXPIRED',
  'CODE_REPLACED',
  'CODE_USED',
  'CODE_ATTEMPTS_EXCEEDED',
];
const needsNewCode: readonly ErrorCode[] = [
  'CODE_EXPIRED',
  'CODE_ATTEMPTS_EXCEEDED',
];
const pointsToLogin: readonly ErrorCode[] = [
  'CODE_USED',
  'ACCOUNT_ALREADY_EXISTS',
];
const pointsToSignup: readonly ErrorCode[] = ['NO_PENDING_REGISTRATION'];

export function RegistrationCodeScreen() {
  const location = useLocation();
  const state = readRegistrationCodeState(location.state);
  const resendButton = useRef<HTMLButtonElement>(null);
  // Enter right after the sixth digit submits before the render that disables the boxes.
  const confirming = useRef(false);
  const mutation = useConfirmRegistration();
  const changingSession = useIsMutating({ mutationKey: ['auth'] }) > 0;
  const session = useQuery({
    ...sessionQueryOptions(),
    // A session kept in the cache may be gone (a protected read just got a 401): ask again on arrival.
    refetchOnMount: 'always',
    enabled: !!state && !changingSession,
  });
  const failure = useSessionCheckFailure(session);
  const checkError = failure?.error ?? session.error;
  const form = useForm<RegistrationCodeValues>({
    resolver: zodResolver(registrationCodeSchema),
    defaultValues: { code: '' },
  });
  const resend = useResendConfirmation();
  const confirmHold = useRetryHold(mutation.error);
  const resendHold = useRetryHold(resend.error);
  // The hour without codes stops the entry and the request alike, whichever of the two was refused.
  const blocker = [mutation.error, resend.error].find(isCodeBlock);
  const blockHold = useRetryHold(blocker);
  const hold = blockHold.held ? blockHold : confirmHold;
  const { setFocus } = form;
  const { field } = useController({ control: form.control, name: 'code' });
  // A refusal speaks of what was sent: it stops describing the boxes once a new code is typed.
  const codeRefused =
    hasCode(mutation.error, codeRefusals) &&
    (field.value === '' || mutation.variables?.code === field.value);
  const error = mutation.error;
  const formatError = form.formState.errors.code?.message;

  // After the render that enables the boxes again: a disabled input does not take the focus.
  useEffect(() => {
    // The resend button is absent while its wait runs: the boxes take the focus instead.
    if (hasCode(error, needsNewCode) && resendButton.current)
      resendButton.current.focus();
    else if (error) setFocus('code');
  }, [error, setFocus]);

  if (!state) return <Navigate to="/inscription" replace />;
  if (mutation.isSuccess) return <Navigate to="/inscription/pseudo" replace />;
  const description = (
    <>
      Email envoyé à {state.email} ·{' '}
      <Link
        to="/inscription"
        state={{ email: state.email, rememberMe: state.rememberMe }}
        aria-label="Modifier l’adresse email"
        className={cn(
          authLinkClass,
          'font-medium text-primary after:-inset-y-3.5',
        )}
      >
        Modifier
      </Link>
    </>
  );
  const failed = !!failure || session.isError;
  const checking =
    (session.isPending && !failure) || (session.isFetching && !!session.data);
  if (checking && !mutation.isPending)
    return (
      <AuthCard title={title} description={description} step={2} placeholder>
        <LoadingStatus loading />
        <RegistrationCodeFormSkeleton />
      </AuthCard>
    );
  // A session the API refused and could not restore is not confirmed: the visitor still needs this form.
  if (session.data && !(session.error instanceof SessionUnconfirmedError))
    return <Navigate to="/" replace />;

  // The check and the resend exclude each other: a resend during the check would replace the code being checked.
  const busy = mutation.isPending || resend.isPending;
  const blocked = hold.held || busy;
  const confirm = async (values: RegistrationCodeValues) => {
    // Sent once per complete code: a held or running check sends nothing, and nothing is replayed later.
    if (blocked || confirming.current) return;
    confirming.current = true;
    try {
      await mutation.mutateAsync({
        ...values,
        registration: state.registration,
      });
    } catch {
      // Emptied after any refusal: the next six digits send again, and nothing is replayed by itself.
      form.setValue('code', '');
    } finally {
      confirming.current = false;
    }
  };
  const submit = (event?: FormEvent) => form.handleSubmit(confirm)(event);
  return (
    <AuthCard title={title} description={description} step={2}>
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
      <AuthForm onSubmit={(event) => void submit(event)}>
        <div className="flex flex-col gap-2">
          <CodeInput
            id="registration-code"
            value={field.value}
            onChange={field.onChange}
            onComplete={() => void submit()}
            inputRef={field.ref}
            disabled={busy}
            invalid={codeRefused || !!formatError}
            describedBy={
              [
                formatError ? formatErrorId : '',
                codeRefused ? errorId : '',
                hold.held ? hold.hintId : '',
              ]
                .filter(Boolean)
                .join(' ') || undefined
            }
          />
          {/* A stable height under the boxes: nothing moves when the message comes or goes. */}
          <div className="min-h-10">
            {formatError ? (
              <FormError id={formatErrorId} className="text-center">
                {formatError}
              </FormError>
            ) : mutation.isError ? (
              <FormError id={errorId} className="text-center">
                <p>{registrationCodeMessage(error)}</p>
                <RetryHint
                  hold={isCodeBlock(error) ? blockHold : confirmHold}
                  hint={isCodeBlock(error) ? codeBlockHint : undefined}
                />
                {hasCode(error, pointsToLogin) ? (
                  <p>
                    <Link to="/connexion">Me connecter</Link>
                  </p>
                ) : null}
                {hasCode(error, pointsToSignup) ? (
                  <p>
                    <Link to="/inscription">Recommencer l’inscription</Link>
                  </p>
                ) : null}
              </FormError>
            ) : null}
          </div>
        </div>
        <p role="status" className="sr-only">
          {mutation.isPending ? 'Vérification…' : ''}
        </p>
        <RetryStatus hold={blocker ? blockHold : confirmHold} />
      </AuthForm>
      <ResendConfirmation
        mutation={resend}
        registration={state.registration}
        resendAt={state.resendAt}
        hold={blockHold.held ? blockHold : resendHold}
        hintHold={isCodeBlock(resend.error) ? blockHold : resendHold}
        statusHold={blocker ? undefined : resendHold}
        buttonRef={resendButton}
        paused={mutation.isPending}
        onSent={() => {
          mutation.reset();
          form.resetField('code');
          setFocus('code');
        }}
      />
    </AuthCard>
  );
}
