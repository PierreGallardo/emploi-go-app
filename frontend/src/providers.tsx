import { useState, type ReactNode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactQueryDevtools } from '@tanstack/react-query-devtools';
import { useAccountChange } from '@/features/auth/session/account-change';
import { useSessionRecovery } from '@/features/auth/session/reauthentication';
import { useSessionSync } from '@/features/auth/session/session-sync';
import { useFocusTracking } from '@/lib/lost-focus';
import { useMediaQuery, wideScreen } from '@/lib/use-media-query';

// On a phone, the floating button of the devtools would cover the footer of the full-screen dialogs.
function QueryDevtools() {
  const wide = useMediaQuery(wideScreen, false);
  return wide ? <ReactQueryDevtools initialIsOpen={false} /> : null;
}

export function AppProviders({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient());
  useAccountChange(client);
  useSessionRecovery(client);
  useSessionSync(client);
  useFocusTracking();
  return (
    <QueryClientProvider client={client}>
      {children}
      {import.meta.env.DEV && <QueryDevtools />}
    </QueryClientProvider>
  );
}
