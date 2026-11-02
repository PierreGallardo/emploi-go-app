import { useEffect } from 'react';
import {
  useQuery,
  useQueryClient,
  type QueryClient,
} from '@tanstack/react-query';
import { render, screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { expect, it, vi } from 'vitest';
import { sessionKey, sessionQueryOptions } from '@/features/auth/queries';
import { datasetsQueryOptions } from '@/features/map/queries';
import { sessionResponse, testSession } from '@/test/handlers/auth';
import { recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { AppProviders } from './providers';

function Catalogue() {
  const session = useQuery(sessionQueryOptions());
  const read = useQuery({
    ...datasetsQueryOptions('jeune_fictif'),
    enabled: session.isSuccess,
  });
  return <p>{read.isError ? 'Lecture refusée' : 'En cours'}</p>;
}

it('installe la reconnexion de l’application : le 401 d’une lecture relit la session', async () => {
  server.use(
    http.get('/api/datasets', () => new HttpResponse(null, { status: 401 })),
  );
  const requests = recordRequests();

  render(
    <AppProviders>
      <Catalogue />
    </AppProviders>,
  );

  // The session is read to show the screen, then again by the reconnection that the 401 started.
  expect(await screen.findByText('Lecture refusée')).toBeVisible();
  await settled();
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(2);
  // The same account is signed in again: the read was sent once more, and refused once more, without a loop.
  expect(requests.of('GET', '/api/datasets')).toHaveLength(2);
});

it('ne renvoie pas la lecture dont la reconnexion découvre un autre compte, et ne garde rien de l’ancien', async () => {
  let reads = 0;
  server.use(
    http.get('/api/auth/session', () =>
      sessionResponse(++reads === 1 ? 'jeune_fictif' : 'autre-jeune_fictif'),
    ),
    http.get('/api/datasets', () => new HttpResponse(null, { status: 401 })),
  );
  const requests = recordRequests();
  let client!: QueryClient;
  function Probe() {
    const current = useQueryClient();
    useEffect(() => {
      client = current;
    }, [current]);
    return null;
  }

  render(
    <AppProviders>
      <Catalogue />
      <Probe />
    </AppProviders>,
  );

  await vi.waitFor(() =>
    expect(client.getQueryData(sessionKey)).toEqual(
      testSession('autre-jeune_fictif'),
    ),
  );
  await settled();
  expect(requests.of('GET', '/api/datasets')).toHaveLength(1);
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(2);
  expect(
    client
      .getQueryCache()
      .getAll()
      .map((query) => query.queryKey),
  ).toEqual([sessionKey]);
});

it('retire la reconnexion au démontage, pour que les suivants puissent l’installer', () => {
  render(<AppProviders>{null}</AppProviders>).unmount();

  expect(() => render(<AppProviders>{null}</AppProviders>)).not.toThrow();
});

it('refuse deux fournisseurs montés en même temps, qui installeraient deux reconnexions', () => {
  render(<AppProviders>{null}</AppProviders>);

  // React reports the error thrown by the layout effect on the console before it rethrows it.
  vi.spyOn(console, 'error').mockImplementation(() => undefined);

  expect(() => render(<AppProviders>{null}</AppProviders>)).toThrow(
    'déjà installée',
  );
});
