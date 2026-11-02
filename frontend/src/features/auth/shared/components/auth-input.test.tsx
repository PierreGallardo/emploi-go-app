import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { authScreens } from '@/test/auth-screens';
import { readsSessionAs, recoveryLink } from '@/test/auth';
import { anonymousResponse } from '@/test/handlers/auth';
import { renderWithProviders } from '@/test/render';

afterEach(() => {
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

const hint =
  '8 caractères minimum, avec une majuscule, une minuscule, un chiffre et un symbole.';
const missing =
  'Il manque : au moins 8 caractères, une majuscule, un chiffre, un symbole.';
const compliant = 'Ton mot de passe respecte la règle.';

const screens = [
  {
    écran: 'l’inscription',
    route: '/inscription',
    champ: 'Mot de passe',
    confirmation: 'Confirmer le mot de passe',
    envoi: 'Créer mon compte',
    fragment: '',
  },
  {
    écran: 'le nouveau mot de passe',
    route: '/reinitialiser-mot-de-passe',
    champ: 'Nouveau mot de passe',
    confirmation: 'Confirmer le nouveau mot de passe',
    envoi: 'Changer mon mot de passe',
    fragment: `#${recoveryLink}`,
  },
];

async function open(route: string, fragment: string, champ: string) {
  readsSessionAs(anonymousResponse);
  window.history.replaceState(null, '', `${route}${fragment}`);
  renderWithProviders(authScreens, { route });
  return screen.findByLabelText(champ, { exact: true });
}

it.each(screens)(
  'sur $écran, l’aide d’une ligne décrit le champ dès l’ouverture',
  async ({ route, fragment, champ }) => {
    const field = await open(route, fragment, champ);

    expect(field).toHaveAccessibleDescription(hint);
  },
);

it.each(screens)(
  'sur $écran, l’erreur liste ce qui manque après l’envoi et l’ajoute à l’aide du champ',
  async ({ route, fragment, champ, confirmation, envoi }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();

    await user.type(field, 'abc');
    await user.type(screen.getByLabelText(confirmation), 'abc');
    await user.click(screen.getByRole('button', { name: envoi }));

    const alert = await screen.findByText(missing);
    expect(alert).toHaveAttribute('role', 'alert');
    expect(field).toHaveAccessibleDescription(`${hint} ${missing}`);
  },
);

it.each(screens)(
  'sur $écran, l’alerte garde son texte jusqu’à la correction, puis disparaît quand le mot de passe est conforme',
  async ({ route, fragment, champ, confirmation, envoi }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();

    await user.type(field, 'abc');
    await user.type(screen.getByLabelText(confirmation), 'abc');
    await user.click(screen.getByRole('button', { name: envoi }));
    const alert = await screen.findByText(missing);

    // The same alert keeps its text while nothing becomes met, so it is not announced at each key.
    await user.type(field, 'd');
    expect(screen.getByText(missing)).toBe(alert);
    await user.type(field, 'D4');
    expect(alert).toHaveTextContent(
      'Il manque : au moins 8 caractères, un symbole.',
    );

    await user.type(field, 'e!f');
    expect(screen.queryByText(/^Il manque/)).not.toBeInTheDocument();
  },
);

it.each(screens)(
  'sur $écran, la région annonce le passage à conforme même quand le mot de passe est collé d’un coup',
  async ({ route, fragment, champ }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();
    const region = screen.getAllByRole('status').at(-1);
    expect(region).toBeEmptyDOMElement();

    await user.click(field);
    await user.paste('abC1!efg');

    expect(screen.getByText(compliant)).toBe(region);
  },
);

it.each(screens)(
  'sur $écran, la région d’annonce se vide quand la saisie redevient non conforme',
  async ({ route, fragment, champ }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();
    const region = screen.getAllByRole('status').at(-1);
    await user.click(field);
    await user.paste('abC1!efg');

    await user.type(field, '{Backspace}{Backspace}');

    expect(region).toBeEmptyDOMElement();
  },
);

it.each(screens)(
  'sur $écran, l’œil affiche puis masque le mot de passe et sa confirmation',
  async ({ route, fragment, champ, confirmation }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();
    const repeat = screen.getByLabelText(confirmation);

    await user.click(
      screen.getByRole('button', { name: 'Afficher le mot de passe' }),
    );
    expect(field).toHaveAttribute('type', 'text');
    expect(repeat).toHaveAttribute('type', 'text');

    await user.click(
      screen.getByRole('button', { name: 'Masquer le mot de passe' }),
    );
    expect(field).toHaveAttribute('type', 'password');
    expect(repeat).toHaveAttribute('type', 'password');
  },
);

it.each(screens)(
  'sur $écran, l’icône de la règle l’ouvre au toucher et la referme avec Échap, et le champ garde la règle en description',
  async ({ route, fragment, champ }) => {
    const field = await open(route, fragment, champ);
    const user = userEvent.setup();

    await user.pointer({
      keys: '[TouchA]',
      target: screen.getByRole('button', { name: 'Règle du mot de passe' }),
    });
    expect(await screen.findByRole('dialog')).toHaveTextContent(hint);

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(field).toHaveAccessibleDescription(hint);
  },
);

it('garde la règle ouverte quand la souris passe de l’icône à la bulle, puis la ferme quand elle s’en va', async () => {
  vi.useFakeTimers({
    shouldAdvanceTime: true,
    toFake: ['setTimeout', 'clearTimeout'],
  });
  await open(screens[0]!.route, '', screens[0]!.champ);
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const icon = screen.getByRole('button', { name: 'Règle du mot de passe' });

  await user.hover(icon);
  const bubble = await screen.findByRole('dialog');
  await user.unhover(icon);
  await user.hover(bubble);
  await act(() => vi.advanceTimersByTimeAsync(500));
  expect(screen.getByRole('dialog')).toBeInTheDocument();

  await user.unhover(bubble);
  await act(() => vi.advanceTimersByTimeAsync(500));
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
});
