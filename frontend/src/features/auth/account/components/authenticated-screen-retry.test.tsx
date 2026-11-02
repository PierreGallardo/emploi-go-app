import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  rateLimitedResponse,
  sessionResponse,
} from '@/test/handlers/auth';
import { readsSessionAs, reconnectable, resumesAs } from '@/test/auth';
import { recordRequests } from '@/test/requests';
import { settled } from '@/test/server';
import {
  MapResource,
  fakeTimers,
  reconnectNetwork,
  resetAccountScreen,
  returnToTab,
  revalidate,
  show,
} from '@/test/account-screen';

afterEach(resetAccountScreen);

it('propose de se connecter avec le mot de passe quand la reprise automatique est limitée', async () => {
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => rateLimitedResponse(30));
  show(<h1>Contenu protégé</h1>);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Trop de tentatives',
  );
  expect(
    screen.queryByRole('heading', { name: 'Contenu protégé' }),
  ).not.toBeInTheDocument();
  await userEvent
    .setup()
    .click(
      screen.getByRole('link', { name: 'Connecte-toi avec ton mot de passe' }),
    );
  expect(
    await screen.findByRole('heading', { name: 'Connexion normale' }),
  ).toBeInTheDocument();
});

it('masque la carte quand la reprise est limitée alors que la session était connue', async () => {
  reconnectable();
  const destroyed = vi.fn();
  const { client } = show(<MapResource destroyed={destroyed} />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(anonymousResponse);
  resumesAs(() => rateLimitedResponse(30));
  await revalidate(client);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Trop de tentatives',
  );
  expect(
    screen.getByRole('link', { name: 'Connecte-toi avec ton mot de passe' }),
  ).toBeVisible();
  expect(
    screen.getByRole('heading', { name: 'Connexion indisponible' }),
  ).toHaveFocus();
  expect(
    screen.queryByRole('button', { name: 'Ressource carte' }),
  ).not.toBeInTheDocument();
  expect(destroyed).toHaveBeenCalledOnce();
});

it('masque la carte quand la session refusée n’est pas restaurée par une panne de la reprise', async () => {
  reconnectable();
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(anonymousResponse);
  resumesAs(() => problemResponse(503));
  await revalidate(client);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'temporairement indisponible',
  );
  expect(
    screen.queryByRole('button', { name: 'Ressource carte' }),
  ).not.toBeInTheDocument();
  expect(
    screen.queryByRole('link', { name: /Connecte-toi/ }),
  ).not.toBeInTheDocument();
});

it('ne relance pas la reprise au retour du focus pendant une attente de plus de cinq minutes', async () => {
  vi.useFakeTimers(fakeTimers);
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => rateLimitedResponse(900));
  const requests = recordRequests();
  show(<h1>Contenu protégé</h1>);
  await screen.findByRole('button', { name: 'Réessayer' });

  await act(() => vi.advanceTimersByTimeAsync(6 * 60_000));
  await returnToTab();
  await settled();

  expect(screen.getByRole('button', { name: 'Réessayer' })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});

it('ne propose pas la connexion par mot de passe pour une autre erreur de vérification', async () => {
  readsSessionAs(() => problemResponse(503));
  show(<h1>Contenu protégé</h1>);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'temporairement indisponible',
  );
  expect(
    screen.queryByRole('link', { name: /Connecte-toi/ }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Réessayer' })).toHaveAttribute(
    'aria-disabled',
    'false',
  );
});

it.each([
  { trigger: 'le retour du focus', act: returnToTab },
  { trigger: 'la reconnexion réseau', act: reconnectNetwork },
])(
  'ne relance pas la reprise sur $trigger pendant l’attente de Retry-After',
  async ({ act: trigger }) => {
    vi.useFakeTimers(fakeTimers);
    reconnectable();
    readsSessionAs(anonymousResponse);
    resumesAs(() => rateLimitedResponse(30));
    const requests = recordRequests();
    show(<h1>Contenu protégé</h1>);
    const retry = await screen.findByRole('button', { name: 'Réessayer' });

    await trigger();
    await act(() => vi.advanceTimersByTimeAsync(29_000));
    await settled();

    expect(retry).toHaveAttribute('aria-disabled', 'true');
    expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
  },
);

it('ne relance pas la reprise au clic sur « Réessayer » pendant l’attente de Retry-After', async () => {
  vi.useFakeTimers(fakeTimers);
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => rateLimitedResponse(30));
  const requests = recordRequests();
  show(<h1>Contenu protégé</h1>);
  const retry = await screen.findByRole('button', { name: 'Réessayer' });
  const reads = requests.of('GET', '/api/auth/session').length;

  await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(retry);
  await settled();

  expect(requests.of('GET', '/api/auth/session')).toHaveLength(reads);
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);
});

it('réactive « Réessayer » à l’échéance de Retry-After et relance la reprise au clic', async () => {
  vi.useFakeTimers(fakeTimers);
  reconnectable();
  readsSessionAs(anonymousResponse);
  resumesAs(() => rateLimitedResponse(30));
  const requests = recordRequests();
  show(<h1>Contenu protégé</h1>);
  const retry = await screen.findByRole('button', { name: 'Réessayer' });

  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(retry).toHaveAttribute('aria-disabled', 'false');
  expect(screen.getByRole('status')).toHaveTextContent('Tu peux réessayer.');
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(1);

  resumesAs(() => sessionResponse());
  await userEvent.setup({ advanceTimers: vi.advanceTimersByTime }).click(retry);
  await screen.findByRole('heading', { name: 'Contenu protégé' });
  expect(requests.of('POST', '/api/auth/resume')).toHaveLength(2);
});
