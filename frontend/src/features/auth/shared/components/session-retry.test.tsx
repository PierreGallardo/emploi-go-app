import { onlineManager } from '@tanstack/react-query';
import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { HttpResponse } from 'msw';
import { Route, Routes } from 'react-router';
import { afterEach, expect, it } from 'vitest';
import { anonymousResponse, problemResponse } from '@/test/handlers/auth';
import { holdNextRead, readsSessionAs } from '@/test/auth';
import { renderWithProviders } from '@/test/render';
import { AuthenticatedScreen } from '../../account/components/authenticated-screen';
import { LoginScreen } from '../../login/components/login-screen';
import { SignupScreen } from '../../signup/components/signup-screen';

afterEach(() => {
  onlineManager.setOnline(true);
});

// « Réessayer » after a failed first check: TanStack Query answers `pending` and drops the error, so the screens must not show their loading state.
const screens = [
  {
    name: 'AuthenticatedScreen',
    route: '/',
    element: (
      <AuthenticatedScreen title="Carte">
        <h1>Carte</h1>
      </AuthenticatedScreen>
    ),
  },
  {
    name: 'LoginScreen',
    route: '/connexion',
    element: <LoginScreen />,
    title: 'Se connecter',
  },
  {
    name: 'SignupScreen',
    route: '/inscription',
    element: <SignupScreen />,
    title: 'Créer un compte',
  },
];

it.each(screens)(
  '$name garde l’erreur et le focus sur « Réessayer » pendant la nouvelle tentative',
  async ({ element, route }) => {
    readsSessionAs(() => HttpResponse.error());
    renderWithProviders(element, { route });
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('Connexion impossible');
    const retry = screen.getByRole('button', { name: 'Réessayer' });
    const answer = holdNextRead();

    await userEvent.setup().click(retry);

    expect(screen.getByRole('alert')).toBe(alert);
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBe(retry);
    expect(retry).toHaveFocus();
    expect(retry).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Vérification en cours…')).toBeVisible();
    expect(screen.queryByText('Chargement…')).not.toBeInTheDocument();
    await act(async () => answer(problemResponse(503)));
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Connexion temporairement indisponible',
    );
    expect(retry).toHaveFocus();
    expect(retry).not.toHaveAttribute('aria-disabled', 'true');
  },
);

it.each(screens)(
  '$name garde l’erreur et annonce l’attente quand la nouvelle tentative attend le réseau',
  async ({ element, route }) => {
    readsSessionAs(() => HttpResponse.error());
    renderWithProviders(element, { route });
    await screen.findByRole('alert');
    const retry = screen.getByRole('button', { name: 'Réessayer' });
    onlineManager.setOnline(false);

    await userEvent.setup().click(retry);

    expect(screen.getByRole('alert')).toHaveTextContent('Connexion impossible');
    expect(screen.getByRole('button', { name: 'Réessayer' })).toBe(retry);
    expect(retry).toHaveFocus();
    expect(retry).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText('Vérification en cours…')).toBeVisible();
    expect(screen.queryByText('Chargement…')).not.toBeInTheDocument();
  },
);

it.each(screens.slice(1))(
  '$name montre le formulaire sans l’erreur et met le focus sur son titre quand la nouvelle tentative trouve un visiteur anonyme',
  async ({ element, route, title }) => {
    readsSessionAs(() => HttpResponse.error());
    renderWithProviders(element, { route });
    await screen.findByRole('alert');
    const answer = holdNextRead();
    await userEvent
      .setup()
      .click(screen.getByRole('button', { name: 'Réessayer' }));

    await act(async () => answer(anonymousResponse()));

    expect(await screen.findByLabelText('Mot de passe')).toBeVisible();
    expect(screen.getByRole('heading', { name: title })).toHaveFocus();
    await waitFor(() =>
      expect(screen.queryByRole('alert')).not.toBeInTheDocument(),
    );
    expect(
      screen.queryByRole('button', { name: 'Réessayer' }),
    ).not.toBeInTheDocument();
  },
);

it('LoginScreen se connecte pendant la nouvelle tentative de « Réessayer »', async () => {
  readsSessionAs(() => HttpResponse.error());
  renderWithProviders(
    <Routes>
      <Route path="/connexion" element={<LoginScreen />} />
      <Route path="/" element={<h1>Carte</h1>} />
    </Routes>,
    { route: '/connexion' },
  );
  await screen.findByRole('alert');
  holdNextRead();
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Réessayer' }));

  await user.type(screen.getByLabelText('Email ou pseudo'), 'jeune_fictif');
  await user.type(screen.getByLabelText('Mot de passe'), 'Une phrase fictive');
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});
