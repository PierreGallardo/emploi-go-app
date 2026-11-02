import { useQuery, useQueryClient } from '@tanstack/react-query';
import { screen } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { useLocation } from 'react-router';
import { expect, it } from 'vitest';
import { sessionKey, sessionQueryOptions } from '@/features/auth/queries';
import { renderWithProviders } from './render';
import { recordRequests } from './requests';
import { server, settled } from './server';
import { testSession } from '@/test/handlers/auth';

function Username() {
  const { data, isPending } = useQuery(sessionQueryOptions());
  if (isPending) return <p>Chargement</p>;
  return <p>{data ? `Connecté : ${data.username}` : 'Anonyme'}</p>;
}

it('affiche la session servie par le réseau simulé', async () => {
  server.use(
    http.get('/api/auth/session', () =>
      HttpResponse.json(testSession('jeune_demo')),
    ),
  );

  renderWithProviders(<Username />);

  expect(await screen.findByText('Connecté : jeune_demo')).toBeVisible();
});

it('affiche la session semée sans la demander au réseau', async () => {
  const requests = recordRequests();

  renderWithProviders(<Username />, {
    seed: (seeded) =>
      seeded.setQueryData(sessionKey, testSession('jeune-semee')),
  });

  expect(await screen.findByText('Connecté : jeune-semee')).toBeVisible();
  await settled();
  expect(requests.of('GET', '/api/auth/session')).toHaveLength(0);
});

it('garde le client et le routeur quand le test redessine l’écran', () => {
  function Provided() {
    const remembered = useQueryClient().getQueryData(['souvenir']);
    return (
      <p>
        {useLocation().pathname} {String(remembered)}
      </p>
    );
  }
  const { rerender } = renderWithProviders(<Provided />, {
    route: '/connexion',
    seed: (client) => client.setQueryData(['souvenir'], 'gardé'),
  });

  rerender(<Provided />);

  expect(screen.getByText('/connexion gardé')).toBeVisible();
});
