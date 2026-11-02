import { useState } from 'react';
import {
  mutationOptions,
  queryOptions,
  useMutation,
  useQueryClient,
  type QueryClient,
  type UseQueryResult,
} from '@tanstack/react-query';
import {
  AuthenticationCoordinationError,
  hasCode,
  SessionUnconfirmedError,
  maxRetryAfterSeconds,
  retryAtOf,
} from './errors';
import {
  chooseUsername,
  confirmRegistration,
  login,
  logout,
  readSession,
  resendConfirmation,
  signup,
  requestRecovery,
  resetPassword,
  startGoogle,
  readGoogleStatus,
  accessGoogleAccount,
  signupGoogle,
  linkGoogle,
  requestGoogleEmail,
  confirmGoogleEmail,
} from './api';
import { announceSessionChange } from './session/session-signal';

// The screens may not import session/ (local/module-boundaries): they read the indicator here.
export { hasReconnectionIndicator } from './session/cookies';

export const sessionKey = ['auth', 'session'] as const;
export const sessionStaleTime = 60_000;
// Kept in the cache: `clear()` (a new login) forgets it, and its gcTime must outlast the longest wait, since no observer holds it.
const resumeLimitKey = ['auth', 'resume-limit'] as const;
const resumeLimitGcTime = (maxRetryAfterSeconds + 1) * 1000;

function rememberResumeLimit(client: QueryClient, error: unknown) {
  // An existing entry would keep the cleanup timer of its first write: start a new one.
  client.removeQueries({ queryKey: resumeLimitKey });
  client.setQueryDefaults(resumeLimitKey, { gcTime: resumeLimitGcTime });
  client.setQueryData(resumeLimitKey, error);
}

function storedResumeLimit(client: QueryClient) {
  const stored = client.getQueryData<unknown>(resumeLimitKey);
  return stored instanceof SessionUnconfirmedError ? stored : undefined;
}

/** Every session read goes through here: it still goes out (another tab may have logged in), but asks for no reconnection while the API asks to wait. */
async function readSessionWithinLimit(
  client: QueryClient,
  signal: AbortSignal,
) {
  try {
    const session = await readSession({
      signal,
      resumeLimit: () => storedResumeLimit(client),
    });
    // A session restored elsewhere (another tab logged in) ends the wait.
    if (session) client.removeQueries({ queryKey: resumeLimitKey });
    return session;
  } catch (error) {
    // Cancelled reads count too: every `clear()` runs after the lock, so after this write.
    if (
      error instanceof SessionUnconfirmedError &&
      error !== storedResumeLimit(client) &&
      retryAtOf(error) !== undefined
    )
      rememberResumeLimit(client, error);
    throw error;
  }
}

export const sessionQueryOptions = () =>
  queryOptions({
    queryKey: sessionKey,
    queryFn: ({ client, signal }) => readSessionWithinLimit(client, signal),
    retry: false,
    staleTime: sessionStaleTime,
    gcTime: 0,
  });

/** The failure of a first session check, kept until the next read answers: TanStack Query drops `error` while refetching, which would swap the focused button for the skeleton. */
export function useSessionCheckFailure(session: UseQueryResult<unknown>) {
  const [held, setHeld] = useState<{ error: unknown }>();
  const failed = session.isError && session.data === undefined;
  if (failed) {
    if (held?.error !== session.error) setHeld({ error: session.error });
  } else if (!session.isPending && held) setHeld(undefined);
  if (failed) return { error: session.error };
  return session.isPending ? held : undefined;
}

/** The `onError` of a session write cut by its deadline: the API may have accepted it, so the session is read again and the write is never resent. */
function refreshSessionAfterTimeout(client: QueryClient) {
  return (error: unknown) => {
    if (
      error instanceof AuthenticationCoordinationError &&
      error.reason === 'timeout'
    ) {
      void client.invalidateQueries({ queryKey: sessionKey });
      // The cookies are shared: the other tabs may hold a session the browser no longer has.
      announceSessionChange();
    }
  };
}

const loginOptions = mutationOptions({
  mutationKey: ['auth', 'login'],
  mutationFn: login,
  retry: false,
  gcTime: 0,
});
const logoutOptions = mutationOptions({
  mutationKey: ['auth', 'logout'],
  mutationFn: logout,
  retry: false,
  gcTime: 0,
});

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    ...loginOptions,
    onError: refreshSessionAfterTimeout(client),
    onMutate: async () => {
      await client.cancelQueries();
    },
    onSuccess: (session) => {
      client.clear();
      client.setQueryData(sessionKey, session);
      announceSessionChange();
    },
  });
}
export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    ...logoutOptions,
    onError: refreshSessionAfterTimeout(client),
    onMutate: async () => {
      await client.cancelQueries();
    },
    onSuccess: () => {
      client.clear();
      client.setQueryData(sessionKey, null);
      announceSessionChange();
    },
  });
}

export function useSignup() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'signup'],
    mutationFn: signup,
    retry: false,
    gcTime: 0,
    onMutate: () => client.cancelQueries(),
  });
}
export function useResendConfirmation() {
  return useMutation({
    mutationKey: ['auth', 'resend'],
    mutationFn: resendConfirmation,
    retry: false,
    gcTime: 0,
  });
}
export function useConfirmRegistration() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'confirmation'],
    mutationFn: confirmRegistration,
    retry: false,
    gcTime: 0,
    onError: refreshSessionAfterTimeout(client),
    onMutate: () => client.cancelQueries(),
    onSuccess: (session) => {
      client.clear();
      client.setQueryData(sessionKey, session);
      announceSessionChange();
    },
  });
}
export function useChooseUsername() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'username'],
    mutationFn: chooseUsername,
    retry: false,
    gcTime: 0,
    onError: (error) => {
      refreshSessionAfterTimeout(client)(error);
      // Chosen in another tab: the session read again leads past this screen.
      if (hasCode(error, ['USERNAME_ALREADY_CHOSEN']))
        void client.invalidateQueries({ queryKey: sessionKey });
    },
    onMutate: () => client.cancelQueries(),
    onSuccess: (session) => {
      client.clear();
      client.setQueryData(sessionKey, session);
      announceSessionChange();
    },
  });
}
export function useRequestRecovery() {
  return useMutation({
    mutationKey: ['auth', 'recovery-request'],
    mutationFn: requestRecovery,
    retry: false,
    gcTime: 0,
  });
}
export function useResetPassword() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'password-reset'],
    mutationFn: resetPassword,
    retry: false,
    gcTime: 0,
    onError: refreshSessionAfterTimeout(client),
    onMutate: () => client.cancelQueries(),
    onSuccess: () => {
      client.clear();
      client.setQueryData(sessionKey, null);
      // The change revoked every session of the account, in every tab.
      announceSessionChange();
    },
  });
}

export const googleStatusKey = ['auth', 'google-status'] as const;
export const googleStatusQueryOptions = () =>
  queryOptions({
    queryKey: googleStatusKey,
    queryFn: ({ signal }) => readGoogleStatus({ signal }),
    retry: false,
    // Keep the terminal result mounted once its server proof is consumed; mutations update the stage explicitly.
    staleTime: Infinity,
    gcTime: 0,
  });
export function useStartGoogle() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'google-start'],
    mutationFn: startGoogle,
    retry: false,
    gcTime: 0,
    onMutate: () => client.cancelQueries(),
    onSuccess: () => client.clear(),
  });
}
export function useGoogleAccess() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'google-access'],
    mutationFn: accessGoogleAccount,
    retry: false,
    gcTime: 0,
    onError: refreshSessionAfterTimeout(client),
    onMutate: () => client.cancelQueries(),
    onSuccess: (session, values) => {
      client.clear();
      client.setQueryData(sessionKey, session);
      client.setQueryData(googleStatusKey, {
        pending: true,
        flowId: values.flowId,
        stage: 'CONFIRM_LINK',
        errorCode: null,
      });
      announceSessionChange();
    },
  });
}
export function useGoogleSignup() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'google-signup'],
    mutationFn: signupGoogle,
    retry: false,
    gcTime: 0,
    onError: refreshSessionAfterTimeout(client),
    onMutate: () => client.cancelQueries(),
    onSuccess: (session) => {
      const status = client.getQueryData(googleStatusKey);
      client.clear();
      client.setQueryData(sessionKey, session);
      if (status) client.setQueryData(googleStatusKey, status);
      announceSessionChange();
    },
  });
}
export function useGoogleLink() {
  const client = useQueryClient();
  return useMutation({
    mutationKey: ['auth', 'google-link'],
    mutationFn: linkGoogle,
    retry: false,
    gcTime: 0,
    onError: refreshSessionAfterTimeout(client),
    onMutate: () => client.cancelQueries(),
    onSuccess: () => {
      const status = client.getQueryData(googleStatusKey);
      client.clear();
      // Preserve this screen while it reports the server's result.
      if (status) client.setQueryData(googleStatusKey, status);
    },
  });
}
export function useRequestGoogleEmail() {
  return useMutation({
    mutationKey: ['auth', 'google-email'],
    mutationFn: requestGoogleEmail,
    retry: false,
    gcTime: 0,
  });
}
export function useConfirmGoogleEmail() {
  return useMutation({
    mutationKey: ['auth', 'google-email-confirm'],
    mutationFn: confirmGoogleEmail,
    retry: false,
    gcTime: 0,
  });
}
