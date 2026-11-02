import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { expect, it, vi } from 'vitest';
import { establishmentDetails } from '@/test/handlers/map';

const detail = { ...establishmentDetails[0]!, name: 'Agence Exemple' };

// The flag is read when the module loads: each test loads it again.
async function load(flag: string | undefined) {
  vi.resetModules();
  vi.stubEnv('VITE_LOCAL_FOLLOW_UP', flag);
  const { EstablishmentActions } = await import('./establishment-actions');
  render(<EstablishmentActions detail={detail} />);
}

it('ouvre la démarche de l’établissement quand la recette l’active', async () => {
  const user = userEvent.setup();
  await load('true');

  await user.click(screen.getByRole('button', { name: 'Démarche' }));

  expect(
    await screen.findByRole('dialog', { name: 'Démarche avec Agence Exemple' }),
  ).toBeInTheDocument();
});

it.each([
  { case: 'sans drapeau', flag: undefined },
  { case: 'avec un drapeau qui n’est pas « true »', flag: 'false' },
])('laisse la démarche grisée $case', async ({ flag }) => {
  const user = userEvent.setup();
  await load(flag);

  await user.click(screen.getByRole('button', { name: 'Démarche' }));

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Démarche' })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
});
