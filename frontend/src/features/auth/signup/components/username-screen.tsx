import { useIsMutating, useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router';
import { LoadingStatus } from '@/components/loading-status';
import { authenticationMessage } from '../../errors';
import { sessionQueryOptions, useChooseUsername } from '../../queries';
import { AuthCard } from '../../shared/components/auth-card';
import { UsernameFormSkeleton } from '../../shared/components/auth-form-skeleton';
import { SessionCheckError } from '../../shared/components/session-check-error';
import { UsernameChoice } from '../../shared/components/username-choice';

/** The last step of the local registration, and of an account that left before it. */
export function UsernameScreen() {
  const mutation = useChooseUsername();
  const changingSession = useIsMutating({ mutationKey: ['auth'] }) > 0;
  const session = useQuery({
    ...sessionQueryOptions(),
    enabled: !changingSession,
  });
  if (mutation.isSuccess) return <Navigate to="/" replace />;
  if (session.data === null) return <Navigate to="/connexion" replace />;
  if (session.data?.username) return <Navigate to="/" replace />;
  if (session.data === undefined && session.isError)
    return (
      <AuthCard title="Connexion indisponible" focusTitle>
        <SessionCheckError
          error={session.error}
          retrying={session.fetchStatus !== 'idle'}
          onRetry={() => void session.refetch()}
          variant="destructive"
        />
      </AuthCard>
    );
  if (session.data === undefined)
    return (
      <AuthCard title="Choisis ton pseudo" step={3} placeholder>
        <LoadingStatus loading />
        <UsernameFormSkeleton />
      </AuthCard>
    );
  return (
    <UsernameChoice
      step={3}
      withTerms={false}
      error={mutation.error}
      message={authenticationMessage(mutation.error)}
      sending={mutation.isPending}
      onChoose={({ username }) => mutation.mutateAsync({ username })}
    />
  );
}
