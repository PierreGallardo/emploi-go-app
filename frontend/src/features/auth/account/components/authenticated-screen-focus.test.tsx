import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse } from 'msw';
import { afterEach, expect, it } from 'vitest';
import { sessionResponse } from '@/test/handlers/auth';
import { holdNextRead, readsSessionAs } from '@/test/auth';
import {
  MapResource,
  resetAccountScreen,
  returnToTab,
  revalidate,
  show,
} from '@/test/account-screen';

afterEach(resetAccountScreen);

it('affiche la coquille de l’application au premier chargement, sans contenu protégé ni texte de vérification', async () => {
  const answer = holdNextRead();
  show(<MapResource />);

  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(screen.getAllByRole('status')).toHaveLength(1);
  const brand = screen.getByRole('link', { name: /Emploi Go/ });
  brand.focus();
  expect(brand).toBeVisible();
  expect(screen.getByRole('button', { name: 'Mon compte' })).toBeDisabled();
  expect(screen.getByRole('main')).toHaveAttribute('aria-busy', 'true');
  expect(
    screen.queryByRole('button', { name: 'Ressource carte' }),
  ).not.toBeInTheDocument();
  expect(screen.queryByText(/Vérification de la connexion/)).toBeNull();
  expect(screen.queryByText(/Connexion en cours/)).toBeNull();

  await act(async () => answer(sessionResponse()));
  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(screen.getByRole('link', { name: /Emploi Go/ })).toBe(brand);
  expect(brand).toHaveFocus();
  expect(screen.getByRole('status')).toHaveTextContent('Chargement terminé.');
  expect(screen.getAllByRole('status')).toHaveLength(1);
  expect(screen.getByRole('main')).not.toHaveAttribute('aria-busy');
  expect(screen.getByRole('button', { name: 'Mon compte' })).toBeEnabled();
});

it('ne déplace pas le focus au premier chargement quand il n’est nulle part', async () => {
  const answer = holdNextRead();
  show(<MapResource />);
  await screen.findByRole('status');

  await act(async () => answer(sessionResponse()));
  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(document.body).toHaveFocus();
});

it('annonce la vérification en cours quand « Réessayer » relit la session', async () => {
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(() => HttpResponse.error());
  await revalidate(client);
  const retry = await screen.findByRole('button', { name: 'Réessayer' });
  const answer = holdNextRead();

  await userEvent.setup().click(retry);

  expect(screen.getByText('Vérification en cours…')).toHaveAttribute(
    'role',
    'status',
  );
  expect(retry).toHaveAttribute('aria-disabled', 'true');
  await act(async () => answer(sessionResponse()));
  await waitFor(() =>
    expect(
      screen.queryByText('Vérification en cours…'),
    ).not.toBeInTheDocument(),
  );
});

it('met le focus sur le contenu quand « Réessayer » rétablit la session après l’erreur plein écran', async () => {
  readsSessionAs(() => HttpResponse.error());
  show(<MapResource />);
  await screen.findByRole('heading', { name: 'Connexion indisponible' });

  readsSessionAs(() => sessionResponse());
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: 'Réessayer' }));

  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(screen.getByRole('main')).toHaveFocus();
});

it('met le focus sur le contenu quand la session revient après l’erreur plein écran, sans clic', async () => {
  readsSessionAs(() => HttpResponse.error());
  show(<MapResource />);
  await screen.findByRole('heading', { name: 'Connexion indisponible' });

  readsSessionAs(() => sessionResponse());
  await returnToTab();

  await screen.findByRole('button', { name: 'Ressource carte' });
  expect(screen.getByRole('main')).toHaveFocus();
});

it('met le focus sur le contenu quand « Réessayer » du bandeau rétablit la lecture', async () => {
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(() => HttpResponse.error());
  await revalidate(client);
  readsSessionAs(() => sessionResponse());
  await userEvent
    .setup()
    .click(await screen.findByRole('button', { name: 'Réessayer' }));

  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
  expect(screen.getByRole('main')).toHaveFocus();
});

it('laisse le focus sur la carte quand le bandeau disparaît', async () => {
  const { client } = show(<MapResource />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  readsSessionAs(() => HttpResponse.error());
  await revalidate(client);
  await screen.findByRole('alert');
  original.focus();
  readsSessionAs(() => sessionResponse());
  await revalidate(client);

  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
  expect(original).toHaveFocus();
});

it('ne prend pas le focus quand le bandeau disparaît sans que le focus y soit passé', async () => {
  const { client } = show(<MapResource />);
  await screen.findByRole('button', { name: 'Ressource carte' });
  readsSessionAs(() => HttpResponse.error());
  await revalidate(client);
  await screen.findByRole('alert');
  readsSessionAs(() => sessionResponse());
  await revalidate(client);

  await waitFor(() =>
    expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
  );
  expect(document.body).toHaveFocus();
});

it('met le focus sur le contenu du nouveau compte quand le focus était dans celui de l’ancien', async () => {
  const { client } = show(<MapResource />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  original.focus();
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  await revalidate(client);

  await waitFor(() => expect(original).not.toBeInTheDocument());
  expect(screen.getByRole('main')).toHaveFocus();
});

it('laisse le focus sur la marque quand le compte change', async () => {
  const { client } = show(<MapResource />);
  const original = await screen.findByRole('button', {
    name: 'Ressource carte',
  });
  const brand = screen.getByRole('link', { name: /Emploi Go/ });
  brand.focus();
  readsSessionAs(() => sessionResponse('autre-jeune_fictif'));
  await revalidate(client);

  await waitFor(() => expect(original).not.toBeInTheDocument());
  expect(brand).toHaveFocus();
});
