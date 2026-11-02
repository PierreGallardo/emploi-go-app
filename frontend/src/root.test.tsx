import { screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { renderWithProviders } from '@/test/render';
import { ErrorBoundary } from './root';

it('affiche « Une erreur est survenue » et un lien vers l’accueil pour une erreur sans statut 404', () => {
  renderWithProviders(
    <ErrorBoundary error={new Error('panne fictive')} params={{}} />,
  );

  expect(
    screen.getByRole('heading', { name: 'Une erreur est survenue' }),
  ).toBeVisible();
  expect(
    screen.getByRole('link', { name: 'Revenir à l’accueil' }),
  ).toHaveAttribute('href', '/');
});
