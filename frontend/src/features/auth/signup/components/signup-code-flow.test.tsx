import { act, screen } from '@testing-library/react';
import userEvent, { type UserEvent } from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  sessionResponse,
  signupAcceptedResponse,
  testRegistration,
} from '@/test/handlers/auth';
import { cachedSession, readsSessionAs } from '@/test/auth';
import { authScreens, termsCheckbox } from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { server } from '@/test/server';

afterEach(() => {
  vi.useRealTimers();
});

const password = 'Une phrase fictive 1 avec accents 🔑';

beforeEach(() => {
  readsSessionAs(anonymousResponse);
});

it('mène de l’inscription au code, puis au pseudo et à la carte, en trois étapes et sans aucune clé dans les requêtes', async () => {
  const bodies: Record<string, Record<string, unknown>> = {};
  let account: string | null | undefined;
  server.use(
    http.get('/api/auth/session', () =>
      account === undefined ? anonymousResponse() : sessionResponse(account),
    ),
    http.post('/api/auth/signup', async ({ request }) => {
      bodies.signup = (await request.json()) as Record<string, unknown>;
      return signupAcceptedResponse();
    }),
    http.post('/api/auth/signup/confirm', async ({ request }) => {
      bodies.confirm = (await request.json()) as Record<string, unknown>;
      account = null;
      return sessionResponse(null);
    }),
    http.post('/api/auth/username', async ({ request }) => {
      bodies.username = (await request.json()) as Record<string, unknown>;
      account = 'jeune_fictif';
      return sessionResponse(account);
    }),
  );
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await user.click(await screen.findByLabelText('Email'));
  await user.paste('jeune@exemple.test');
  expect(screen.getByText('Étape 1 sur 3')).toBeInTheDocument();
  await user.click(screen.getByLabelText('Mot de passe'));
  await user.paste(password);
  await user.click(screen.getByLabelText('Confirmer le mot de passe'));
  await user.paste(password);
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));

  const code = await screen.findByRole('textbox', {
    name: 'Code à 6 chiffres reçu par email',
  });
  expect(screen.getByText('Étape 2 sur 3')).toBeInTheDocument();
  await user.type(code, '654321');

  await user.click(await screen.findByLabelText('Pseudo'));
  await user.paste('jeune_fictif');
  expect(screen.getByText('Étape 3 sur 3')).toBeInTheDocument();
  await user.click(screen.getByRole('button', { name: 'Continuer' }));

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
  expect(bodies.signup).toEqual({
    email: 'jeune@exemple.test',
    password,
    confirmation: password,
    acceptedTerms: true,
    rememberMe: false,
  });
  expect(bodies.confirm).toEqual({
    code: '654321',
    registration: testRegistration,
  });
  expect(bodies.username).toEqual({ username: 'jeune_fictif' });
});

async function fillSignup(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText('Email'), 'jeune@exemple.test');
  await user.type(screen.getByLabelText('Mot de passe'), password);
  await user.type(screen.getByLabelText('Confirmer le mot de passe'), password);
  await user.click(termsCheckbox());
  await user.click(screen.getByRole('button', { name: 'Créer mon compte' }));
}

it('montre, après une réponse 202, un écran sans alerte ni mot sur l’état de l’email, avec un chemin pour changer d’adresse', async () => {
  server.use(http.post('/api/auth/signup', () => signupAcceptedResponse()));
  const { client } = renderWithProviders(authScreens, {
    route: '/inscription',
  });
  const user = userEvent.setup();
  await fillSignup(user);

  await screen.findByRole('textbox', {
    name: 'Code à 6 chiffres reçu par email',
  });

  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.queryByText(/déjà/)).toBeNull();
  expect(
    screen.getByRole('link', { name: 'Modifier l’adresse email' }),
  ).toHaveAttribute('href', '/inscription');
  // The anonymous session is already known: the code screen shows its form, not a skeleton.
  expect(cachedSession(client)).toBeNull();
  expect(screen.queryByText('Chargement…')).toBeNull();
});

it.each([
  {
    code: 'WEAK_PASSWORD',
    status: 400,
    field: 'Mot de passe',
    message:
      'Ce mot de passe ne respecte pas la règle : 8 à 128 caractères, avec une majuscule, une minuscule, un chiffre et un caractère spécial.',
  },
])(
  'relie le refus $code à son champ et lui rend le focus',
  async ({ code, status, field, message }) => {
    server.use(
      http.post('/api/auth/signup', () => problemResponse(status, code)),
    );
    renderWithProviders(authScreens, { route: '/inscription' });
    const user = userEvent.setup();
    await fillSignup(user);

    await screen.findByRole('alert');

    const input = screen.getByLabelText(field, { exact: true });
    expect(input).toBeInvalid();
    expect(input).toHaveAccessibleDescription(expect.stringContaining(message));
    expect(input).toHaveFocus();
  },
);

it('« Modifier » rend le formulaire avec l’email, sans les mots de passe', async () => {
  server.use(http.post('/api/auth/signup', () => signupAcceptedResponse()));
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await fillSignup(user);

  await user.click(
    await screen.findByRole('link', { name: 'Modifier l’adresse email' }),
  );

  expect(await screen.findByLabelText('Email')).toHaveValue(
    'jeune@exemple.test',
  );
  expect(screen.getByLabelText('Mot de passe')).toHaveValue('');
  expect(screen.getByLabelText('Confirmer le mot de passe')).toHaveValue('');
});

it.each([
  {
    label: 'cochée',
    choose: (user: UserEvent, box: HTMLElement) => user.click(box),
    checked: true,
  },
  { label: 'décochée', choose: async () => {}, checked: false },
])(
  '« Modifier » rend la case « Rester connecté » $label',
  async ({ choose, checked }) => {
    server.use(http.post('/api/auth/signup', () => signupAcceptedResponse()));
    renderWithProviders(authScreens, { route: '/inscription' });
    const user = userEvent.setup();
    await choose(
      user,
      await screen.findByRole('checkbox', { name: 'Rester connecté' }),
    );
    await fillSignup(user);

    await user.click(
      await screen.findByRole('link', { name: 'Modifier l’adresse email' }),
    );

    expect(
      await screen.findByRole('checkbox', { name: 'Rester connecté' }),
    ).toHaveProperty('checked', checked);
  },
);

it('suspend le renvoi pendant l’attente annoncée par l’inscription, en comptant les secondes, puis annonce sa fin', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  server.use(
    http.post('/api/auth/signup', () =>
      signupAcceptedResponse({ 'Retry-After': '45' }),
    ),
  );
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await fillSignup(user);

  const waiting = await screen.findByRole('button', {
    name: 'Renvoyer dans 45 s',
  });
  expect(waiting).toHaveAttribute('aria-disabled', 'true');
  await act(() => vi.advanceTimersByTimeAsync(1_000));
  expect(
    screen.getByRole('button', { name: 'Renvoyer dans 44 s' }),
  ).toBeInTheDocument();
  await act(() => vi.advanceTimersByTimeAsync(44_000));

  expect(
    screen.getByRole('button', { name: 'Renvoyer le code' }),
  ).toHaveAttribute('aria-disabled', 'false');
  expect(screen.getByText('Tu peux renvoyer l’email.')).toBeInTheDocument();
});

it('suspend de nouveau le renvoi pendant l’attente annoncée par un renvoi accepté', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  server.use(
    http.post('/api/auth/signup', () => signupAcceptedResponse()),
    http.post(
      '/api/auth/signup/resend',
      () =>
        new HttpResponse(null, {
          status: 202,
          headers: { 'Retry-After': '60' },
        }),
    ),
  );
  renderWithProviders(authScreens, { route: '/inscription' });
  const user = userEvent.setup();
  await fillSignup(user);

  await user.click(
    await screen.findByRole('button', { name: 'Renvoyer le code' }),
  );

  expect(
    await screen.findByRole('button', { name: 'Renvoyer dans 60 s' }),
  ).toHaveAttribute('aria-disabled', 'true');
});
