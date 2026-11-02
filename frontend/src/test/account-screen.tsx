import { useEffect } from 'react';
import {
  focusManager,
  onlineManager,
  type QueryClient,
} from '@tanstack/react-query';
import { act } from '@testing-library/react';
import { http } from 'msw';
import { Route, Routes } from 'react-router';
import { expect, vi } from 'vitest';
import { AuthenticatedScreen } from '@/features/auth/account/components/authenticated-screen';
import { sessionKey } from '@/features/auth/queries';
import { anonymousResponse, sessionResponse } from './handlers/auth';
import { holdNextRead, readsSessionAs } from './auth';
import { otherTab } from './broadcast';
import { renderWithProviders } from './render';
import { server, settled } from './server';

// `shouldAdvanceTime` lets the clock run while the test awaits the server; tests move it forward explicitly.
export const fakeTimers: Parameters<typeof vi.useFakeTimers>[0] = {
  shouldAdvanceTime: true,
  toFake: ['Date', 'setTimeout', 'clearTimeout'],
};

/** Restores the focus, the network and the clock that a test of the account screen may have changed. */
export function resetAccountScreen() {
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
  vi.useRealTimers();
}

export function show(
  children: React.ReactNode | ((scope: string) => React.ReactNode),
  prefetch?: () => void,
) {
  return renderWithProviders(
    <Routes>
      <Route
        path="/"
        element={
          <AuthenticatedScreen title="Carte" prefetch={prefetch}>
            {children}
          </AuthenticatedScreen>
        }
      />
      <Route path="/connexion" element={<h1>Connexion normale</h1>} />
    </Routes>,
  );
}

export async function revalidate(client: QueryClient) {
  await act(() => client.invalidateQueries({ queryKey: sessionKey }));
}

export async function whileReading(client: QueryClient, during: () => void) {
  const answer = holdNextRead();
  let reading!: Promise<void>;
  await act(async () => {
    reading = client.invalidateQueries({ queryKey: sessionKey });
  });
  try {
    expect(client.isFetching({ queryKey: sessionKey })).toBe(1);
    during();
  } finally {
    await act(async () => {
      answer(sessionResponse());
      await reading;
    });
  }
}

export async function returnToTab() {
  await act(async () => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
  });
}

export async function reconnectNetwork() {
  await act(async () => {
    onlineManager.setOnline(false);
    onlineManager.setOnline(true);
  });
}

export async function anotherTabChangesTheSession() {
  await act(async () => {
    otherTab().channel.postMessage('session-changed');
    await settled();
  });
}

export function MapResource({
  mounted,
  destroyed,
}: {
  mounted?: () => void;
  destroyed?: () => void;
}) {
  useEffect(() => {
    mounted?.();
    return destroyed;
  }, [mounted, destroyed]);
  return <button>Ressource carte</button>;
}

/** The first read of the session succeeds; the following ones find no session. */
export function sessionExpiresAfterFirstRead() {
  readsSessionAs(anonymousResponse);
  server.use(
    http.get('/api/auth/session', () => sessionResponse(), { once: true }),
  );
}
