import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { expect, it } from 'vitest';
import { AccountHeader } from './account-header';

async function openSettings() {
  const user = userEvent.setup();
  render(
    <MemoryRouter>
      <AccountHeader
        account={{ username: 'jeune', loggingOut: false, onLogout: () => {} }}
      />
    </MemoryRouter>,
  );

  await user.click(screen.getByRole('button', { name: 'Mon compte' }));
  await user.click(screen.getByRole('menuitem', { name: 'Paramètres' }));

  return {
    user,
    dialog: await screen.findByRole('dialog', { name: 'Paramètres' }),
  };
}

it('« Paramètres » du menu du compte ouvre sa fenêtre avec le focus à l’intérieur', async () => {
  const { dialog } = await openSettings();

  expect(dialog).toContainElement(document.activeElement as HTMLElement);
});

it('Échap ferme la fenêtre « Paramètres » et rend le focus à « Mon compte »', async () => {
  const { user } = await openSettings();

  await user.keyboard('{Escape}');

  expect(
    screen.queryByRole('dialog', { name: 'Paramètres' }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Mon compte' })).toHaveFocus();
});

it('ne montre que le logo, qui reste un lien nommé vers l’accueil', () => {
  render(
    <MemoryRouter>
      <AccountHeader
        account={{ username: 'jeune', loggingOut: false, onLogout: () => {} }}
      />
    </MemoryRouter>,
  );

  expect(
    screen.getByRole('link', { name: 'Emploi Go, accueil' }),
  ).toHaveAttribute('href', '/');
  expect(screen.queryByText('Mission Locale de Dijon')).not.toBeInTheDocument();
  expect(screen.queryByText('Emploi Go')).not.toBeInTheDocument();
});
