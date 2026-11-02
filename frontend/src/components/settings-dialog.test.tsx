import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createRef, useRef, useState } from 'react';
import { expect, it } from 'vitest';
import { setTheme, themeKey, type ThemeChoice } from '@/lib/theme';
import { SettingsDialog } from './settings-dialog';

it.each<{ label: string; theme: ThemeChoice }>([
  { label: 'Clair', theme: 'light' },
  { label: 'Sombre', theme: 'dark' },
  { label: 'Système', theme: 'system' },
])(
  'la fenêtre « Paramètres » coche « $label » quand c’est le thème actuel',
  ({ label, theme }) => {
    setTheme(theme);
    render(
      <SettingsDialog
        open
        onOpenChange={() => {}}
        returnFocusTo={createRef()}
      />,
    );

    const dialog = screen.getByRole('dialog', { name: 'Paramètres' });
    expect(dialog).toBeVisible();
    expect(screen.getByRole('radiogroup', { name: 'Thème' })).toBeVisible();
    expect(screen.getByRole('radio', { name: label })).toBeChecked();
  },
);

it.each<{
  label: string;
  initial: ThemeChoice;
  stored: string | null;
  applied: string | undefined;
}>([
  { label: 'Clair', initial: 'dark', stored: 'light', applied: 'light' },
  { label: 'Sombre', initial: 'light', stored: 'dark', applied: 'dark' },
  { label: 'Système', initial: 'dark', stored: null, applied: undefined },
])(
  'choisir « $label » l’applique et le garde tout de suite, fenêtre ouverte',
  async ({ label, initial, stored, applied }) => {
    const user = userEvent.setup();
    setTheme(initial);
    render(
      <SettingsDialog
        open
        onOpenChange={() => {}}
        returnFocusTo={createRef()}
      />,
    );

    await user.click(screen.getByRole('radio', { name: label }));

    expect(screen.getByRole('radio', { name: label })).toBeChecked();
    expect(document.documentElement.dataset.theme).toBe(applied);
    expect(localStorage.getItem(themeKey)).toBe(stored);
    expect(screen.getByRole('dialog', { name: 'Paramètres' })).toBeVisible();
  },
);

function OpenableSettings() {
  const trigger = useRef<HTMLButtonElement>(null);
  const [open, setOpen] = useState(false);
  return (
    <>
      <button ref={trigger} onClick={() => setOpen(true)}>
        Ouvrir les paramètres
      </button>
      <SettingsDialog
        open={open}
        onOpenChange={setOpen}
        returnFocusTo={trigger}
      />
    </>
  );
}

it('rend le focus au bouton qui a ouvert la fenêtre quand elle se ferme', async () => {
  const user = userEvent.setup();
  render(<OpenableSettings />);
  await user.click(
    screen.getByRole('button', { name: 'Ouvrir les paramètres' }),
  );

  await user.keyboard('{Escape}');

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  expect(
    screen.getByRole('button', { name: 'Ouvrir les paramètres' }),
  ).toHaveFocus();
});
