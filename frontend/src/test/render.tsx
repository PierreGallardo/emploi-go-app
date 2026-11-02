import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { MemoryRouter } from 'react-router';
import { onTestFinished } from 'vitest';
import { useAccountChange } from '@/features/auth/session/account-change';
import { useSessionRecovery } from '@/features/auth/session/reauthentication';
import { useSessionSync } from '@/features/auth/session/session-sync';
import { useFocusTracking } from '@/lib/lost-focus';

/**
 * For a test that renders its own providers (`render(…, { wrapper })`): without it, `lost-focus` keeps the screen of the previous test.
 */
export function FocusTracking({ children }: { children: ReactNode }) {
  useFocusTracking();
  return children;
}

export function renderWithProviders(
  ui: ReactElement,
  {
    route = '/',
    seed,
  }: {
    route?: string | { pathname: string; state: unknown };
    seed?: (client: QueryClient) => void;
  } = {},
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  onTestFinished(() => client.clear());
  seed?.(client);
  // `wrapper` keeps the providers when the test calls `rerender`.
  function Providers({ children }: { children: ReactNode }) {
    useAccountChange(client);
    useSessionRecovery(client);
    useSessionSync(client);
    return (
      <FocusTracking>
        <QueryClientProvider client={client}>
          <MemoryRouter initialEntries={[route]}>{children}</MemoryRouter>
        </QueryClientProvider>
      </FocusTracking>
    );
  }
  return { client, ...render(ui, { wrapper: Providers }) };
}
