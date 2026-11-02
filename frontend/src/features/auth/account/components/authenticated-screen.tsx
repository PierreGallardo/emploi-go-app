import { useEffect, useRef, type ReactNode } from 'react';
import { useIsMutating, useQuery } from '@tanstack/react-query';
import { Navigate } from 'react-router';
import { AuthLayout } from '@/components/auth-layout';
import { LoadingStatus } from '@/components/loading-status';
import { PageTitle } from '@/components/page-title';
import { focusWasLost, markScreenShown } from '@/lib/lost-focus';
import { Alert, AlertDescription } from '@/components/ui/alert';
import { SessionUnconfirmedError, authenticationMessage } from '../../errors';
import {
  hasReconnectionIndicator,
  sessionQueryOptions,
  useLogout,
  useSessionCheckFailure,
} from '../../queries';
import { Skeleton } from '@/components/ui/skeleton';
import { AccountHeader } from './account-header';
import { AuthCard } from '../../shared/components/auth-card';
import { SessionCheckError } from '../../shared/components/session-check-error';

export function AuthenticatedScreen({
  title,
  fullBleed = false,
  prefetch,
  children,
}: {
  /** The document title while the shell is on screen, loading included: no `h1` exists yet. */
  title: string;
  /** The content fills the viewport below the header, edge to edge, and the page does not scroll. */
  fullBleed?: boolean;
  /** Asks for what the content needs while the session is checked, when the browser shows a sign of an account. */
  prefetch?: () => void;
  children: ReactNode | ((scope: string) => ReactNode);
}) {
  const logout = useLogout();
  useEffect(() => {
    // An anonymous visitor downloads nothing for a screen it will not see.
    if (prefetch && hasReconnectionIndicator()) prefetch();
  }, [prefetch]);
  const changingSession = useIsMutating({ mutationKey: ['auth'] }) > 0;
  const session = useQuery({
    ...sessionQueryOptions(),
    enabled: !changingSession,
  });
  const failure = useSessionCheckFailure(session);
  // A failed logout belongs to the account that asked for it: another account starts without its message.
  const username = session.data?.username ?? undefined;
  const { reset: resetLogout } = logout;
  useEffect(() => {
    resetLogout();
  }, [username, resetLogout]);
  const checkError = (variant: 'default' | 'destructive') => (
    <SessionCheckError
      error={failure?.error ?? session.error}
      retrying={session.fetchStatus !== 'idle'}
      onRetry={() => void session.refetch()}
      variant={variant}
    />
  );
  const redirected = logout.isSuccess || session.data === null;
  // A failed read keeps the last known session; the error screen stays during a new read, so its focused button is not replaced.
  const unavailable =
    failure !== undefined || session.error instanceof SessionUnconfirmedError;
  const account = redirected || unavailable ? undefined : username;
  const loading = !redirected && !unavailable && account === undefined;
  const notice = account !== undefined && session.isError;
  const main = useRef<HTMLElement>(null);
  const hadNotice = useRef(false);
  // Not on every render: the menu hands the focus back to its trigger a moment after it closes, and a render in between would take it.
  useEffect(() => {
    // The skeleton holds no focus and is not a screen that was read: the first load moves nothing.
    if (redirected || loading) return;
    // A notice that appears removes nothing: an earlier loss is not this screen's to repair.
    const appeared = notice && !hadNotice.current;
    hadNotice.current = notice;
    if (!appeared && focusWasLost()) main.current?.focus();
    markScreenShown(main.current);
  }, [redirected, loading, account, notice]);
  if (redirected) return <Navigate to="/connexion" replace />;
  // An account that left its registration before the last step chooses its username first.
  if (!unavailable && session.data?.username === null)
    return <Navigate to="/inscription/pseudo" replace />;
  if (unavailable)
    return (
      <AuthLayout>
        <AuthCard key="unavailable" title="Connexion indisponible" focusTitle>
          {checkError('destructive')}
        </AuthCard>
      </AuthLayout>
    );
  if (account === undefined)
    return (
      <div>
        <PageTitle>{title}</PageTitle>
        <LoadingStatus loading focusSpeaks={false} />
        <AccountHeader fullBleed={fullBleed} />
        <main
          id="main-content"
          aria-busy="true"
          className="mx-auto max-w-7xl px-4 py-6 sm:px-6"
        >
          <div aria-hidden="true" className="flex flex-col gap-6">
            <Skeleton className="h-9 w-40" />
            <Skeleton className="h-72 w-full" />
            <div className="flex flex-col gap-3">
              <Skeleton className="h-20 w-full" />
              <Skeleton className="h-20 w-full" />
              <Skeleton className="h-20 w-full" />
            </div>
          </div>
        </main>
      </div>
    );
  // A check that confirms the same account changes nothing visible; another account remounts the content.
  return (
    <div
      className={fullBleed ? 'flex h-dvh flex-col overflow-hidden' : undefined}
    >
      <PageTitle>{title}</PageTitle>
      <LoadingStatus loading={false} focusSpeaks={false} />
      <AccountHeader
        fullBleed={fullBleed}
        account={{
          username: account,
          loggingOut: logout.isPending,
          onLogout: () => logout.mutate(),
        }}
      />
      <div
        key={account}
        className={fullBleed ? 'flex min-h-0 flex-1 flex-col' : undefined}
      >
        {session.isError ? (
          <div className="mx-auto flex w-full max-w-7xl shrink-0 flex-col gap-3 px-4 pt-4 sm:px-6">
            {checkError('default')}
          </div>
        ) : null}
        <main
          id="main-content"
          ref={main}
          tabIndex={-1}
          className={
            fullBleed
              ? 'flex min-h-0 flex-1 flex-col outline-none'
              : 'mx-auto max-w-7xl px-4 py-6 outline-none sm:px-6'
          }
        >
          {logout.isError ? (
            <Alert
              variant="destructive"
              className={fullBleed ? 'm-4 w-auto shrink-0' : 'mb-5'}
            >
              <AlertDescription>
                {authenticationMessage(logout.error)}
              </AlertDescription>
            </Alert>
          ) : null}
          {typeof children === 'function' ? children(account) : children}
        </main>
      </div>
    </div>
  );
}
