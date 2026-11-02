import { act, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  rateLimitedResponse,
} from '@/test/handlers/auth';
import { authScreens, termsCheckbox } from '@/test/auth-screens';
import {
  googleStage,
  lastStatus,
  readsSessionAs,
  recoveryLink,
} from '@/test/auth';
import { renderWithProviders } from '@/test/render';
import { holdResponse, recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';

afterEach(() => {
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

type User = ReturnType<typeof userEvent.setup>;

type Sender = {
  name: string;
  route: string;
  /** The fragment of the link the screen opens with, when it reads one. */
  fragment?: string;
  fill: (user: User) => Promise<void>;
  button: string;
  path: string;
};
type Form = Sender & { field: string; other: string };
/** The quota counts the field: the same value (another case, edge spaces) stays held, another value is not. */
type ByIdentity = Form & { same: string };

const password = 'Une phrase fictive 1 avec accents 🔑';

const byIdentity: ByIdentity[] = [
  {
    name: 'la connexion',
    route: '/connexion',
    fill: async (user) => {
      await user.type(
        await screen.findByLabelText('Email ou pseudo'),
        'jeune_demo',
      );
      await user.type(screen.getByLabelText('Mot de passe'), password);
    },
    button: 'Se connecter',
    path: '/api/auth/login',
    field: 'Email ou pseudo',
    same: '  Jeune_DEMO ',
    other: 'autre-jeune',
  },
  {
    name: 'l’inscription',
    route: '/inscription',
    fill: async (user) => {
      await user.type(
        await screen.findByLabelText('Email'),
        'jeune@exemple.fr',
      );
      await user.type(screen.getByLabelText('Mot de passe'), password);
      await user.type(
        screen.getByLabelText('Confirmer le mot de passe'),
        password,
      );
      await user.click(termsCheckbox());
    },
    button: 'Créer mon compte',
    path: '/api/auth/signup',
    field: 'Email',
    same: '  Jeune@Exemple.FR ',
    other: 'autre@exemple.fr',
  },
  {
    name: 'le mot de passe oublié',
    route: '/mot-de-passe-oublie',
    fill: async (user) => {
      await user.type(
        await screen.findByLabelText('Email'),
        'jeune@exemple.fr',
      );
    },
    button: 'Envoyer la demande',
    path: '/api/auth/password/forgot',
    field: 'Email',
    same: '  Jeune@Exemple.FR ',
    other: 'autre@exemple.fr',
  },
  {
    name: 'la vérification du compte pour Google',
    route: '/connexion-google',
    fill: async (user) => {
      googleStage('ACCOUNT_ACCESS_REQUIRED');
      await user.type(
        await screen.findByLabelText('Email ou pseudo'),
        'jeune_demo',
      );
      await user.type(screen.getByLabelText('Mot de passe'), password);
    },
    button: 'Vérifier mon compte',
    path: '/api/auth/google/access',
    field: 'Email ou pseudo',
    same: '  Jeune_DEMO ',
    other: 'autre-jeune',
  },
];

const newPassword = ' Une phrase fictive 1 avec accents 🔑 ';

async function fillNewPassword(user: User) {
  await user.type(
    await screen.findByLabelText('Nouveau mot de passe', { exact: true }),
    newPassword,
  );
  await user.type(
    screen.getByLabelText('Confirmer le nouveau mot de passe'),
    newPassword,
  );
}

const nothingToCompare: Form[] = [
  {
    name: 'le nouveau mot de passe',
    route: '/reinitialiser-mot-de-passe',
    fragment: `#${recoveryLink}`,
    fill: fillNewPassword,
    button: 'Changer mon mot de passe',
    path: '/api/auth/password/reset',
    field: 'Nouveau mot de passe',
    other: 'Un autre mot de passe fictif',
  },
  {
    name: 'le pseudo du compte Google',
    route: '/connexion-google',
    fill: async (user) => {
      googleStage('CHOOSE_USERNAME');
      await user.type(await screen.findByLabelText('Pseudo'), 'jeune_demo');
      await user.click(termsCheckbox());
    },
    button: 'Continuer',
    path: '/api/auth/google/signup',
    field: 'Pseudo',
    other: 'autre-pseudo',
  },
];
const buttonsOnly: Sender[] = [
  {
    name: 'le lien d’email de Google',
    route: '/connexion-google',
    fill: async () => {
      googleStage('EMAIL_CONFIRMATION_REQUIRED');
      await screen.findByRole('button', { name: 'Envoyer le lien' });
    },
    button: 'Envoyer le lien',
    path: '/api/auth/google/email',
  },
  {
    name: 'l’association de Google',
    route: '/connexion-google',
    fill: async () => {
      googleStage('CONFIRM_LINK');
      await screen.findByRole('button', {
        name: 'Confirmer l’association de Google',
      });
    },
    button: 'Confirmer l’association de Google',
    path: '/api/auth/google/link',
  },
  {
    name: 'la confirmation de l’email de Google',
    route: '/confirmation-google',
    fragment: `#${recoveryLink}`,
    fill: async () => {
      const confirm = await screen.findByRole('button', {
        name: 'Confirmer mon email',
      });
      await waitFor(() => expect(confirm).toBeEnabled());
    },
    button: 'Confirmer mon email',
    path: '/api/auth/google/email/confirm',
  },
  {
    name: 'le départ vers Google',
    route: '/connexion',
    fill: async () => {
      await screen.findByRole('button', { name: 'Continuer avec Google' });
    },
    button: 'Continuer avec Google',
    path: '/api/auth/google/start',
  },
];
const forms: Form[] = [...byIdentity, ...nothingToCompare];
const scenarios: Sender[] = [...forms, ...buttonsOnly];

/** The clock starts at 14:30:10 and keeps running: user-event and the screens wait on timers. */
async function show(scenario: Sender, seconds = 30) {
  readsSessionAs(anonymousResponse);
  window.history.replaceState(
    null,
    '',
    `${scenario.route}${scenario.fragment ?? ''}`,
  );
  renderWithProviders(authScreens, { route: scenario.route });
  await scenario.fill(userEvent.setup());
  vi.useFakeTimers({
    now: new Date(2026, 9, 17, 14, 30, 10),
    shouldAdvanceTime: true,
    toFake: ['Date', 'setTimeout', 'clearTimeout'],
  });
  server.use(http.post(scenario.path, () => rateLimitedResponse(seconds)));
  return {
    user: userEvent.setup({ advanceTimers: vi.advanceTimersByTime }),
    requests: recordRequests(),
    button: () => screen.getByRole('button', { name: scenario.button }),
  };
}

async function sendLimited(user: User, button: () => HTMLElement) {
  await user.click(button());
  await screen.findByText(/Tu pourras réessayer/);
}

const refusalAlert = () =>
  screen
    .getAllByRole('alert')
    .find((alert) => within(alert).queryByText(/Tu pourras réessayer/));

it.each(scenarios)(
  'met l’heure de la fin du délai dans l’alerte du refus pour $name, reliée au bouton',
  async (scenario) => {
    const { user, button } = await show(scenario);

    await sendLimited(user, button);

    expect(refusalAlert()).toHaveTextContent(
      'Trop de tentatives. Réessaie plus tard.',
    );
    expect(button()).toHaveAccessibleDescription(
      'Tu pourras réessayer à 14:31.',
    );
  },
);

it.each(scenarios)(
  'suspend le bouton pour $name sans lui retirer le focus',
  async (scenario) => {
    const { user, button } = await show(scenario);

    await sendLimited(user, button);

    expect(button()).toHaveAttribute('aria-disabled', 'true');
    expect(button()).toBeEnabled();
    expect(button()).toHaveFocus();
  },
);

it.each(scenarios)(
  'n’envoie rien pour $name pendant le délai au clic',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    await sendLimited(user, button);

    await user.click(button());

    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
  },
);

it.each(forms)(
  'n’envoie rien pour $name pendant le délai à la touche Entrée dans un champ',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    await sendLimited(user, button);

    await user.type(
      screen.getByLabelText(scenario.field, { exact: true }),
      '{Enter}',
    );

    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
  },
);

it.each(scenarios)(
  'garde le bouton pour $name suspendu jusqu’à l’échéance exacte',
  async (scenario) => {
    const { user, button } = await show(scenario);
    await sendLimited(user, button);

    await act(() => vi.advanceTimersByTimeAsync(29_000));

    expect(button()).toHaveAttribute('aria-disabled', 'true');
  },
);

it.each(scenarios)(
  'réactive à l’échéance le même bouton pour $name, qui garde le focus',
  async (scenario) => {
    const { user, button } = await show(scenario);
    await sendLimited(user, button);
    const sent = button();

    await act(() => vi.advanceTimersByTimeAsync(30_000));

    expect(button()).toBe(sent);
    expect(sent).not.toHaveAttribute('aria-disabled', 'true');
    expect(sent).not.toHaveAccessibleDescription();
    expect(sent).toHaveFocus();
  },
);

it.each(scenarios)(
  'annonce une seule fois la fin du délai pour $name, sans rien dire avant',
  async (scenario) => {
    const { user, button } = await show(scenario);
    await sendLimited(user, button);
    expect(lastStatus()).toBeEmptyDOMElement();
    await act(() => vi.advanceTimersByTimeAsync(29_000));
    expect(lastStatus()).toBeEmptyDOMElement();

    await act(() => vi.advanceTimersByTimeAsync(1_000));

    expect(screen.getAllByText('Tu peux réessayer.')).toHaveLength(1);
    expect(lastStatus()).toHaveTextContent('Tu peux réessayer.');
  },
);

it.each(byIdentity)(
  'ne retient pas pour $name une autre identité que celle du délai',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    await sendLimited(user, button);
    const field = screen.getByLabelText(scenario.field, { exact: true });

    await user.clear(field);
    await user.type(field, scenario.other);

    expect(button()).not.toHaveAttribute('aria-disabled', 'true');
    expect(button()).not.toHaveAccessibleDescription();
    expect(screen.queryByText(/Tu pourras réessayer/)).not.toBeInTheDocument();
    // The wait of the first address ends: nobody is told to try again for an address that was not held.
    await act(() => vi.advanceTimersByTimeAsync(30_000));
    expect(screen.queryByText('Tu peux réessayer.')).not.toBeInTheDocument();
    await user.click(button());
    await vi.waitFor(() =>
      expect(requests.of('POST', scenario.path)).toHaveLength(2),
    );
  },
);

it.each(byIdentity)(
  'retient pour $name la même identité à la casse et aux espaces de bord près',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    await sendLimited(user, button);
    const field = screen.getByLabelText(scenario.field, { exact: true });

    await user.clear(field);
    await user.type(field, scenario.same);
    await user.click(button());

    expect(button()).toHaveAttribute('aria-disabled', 'true');
    expect(button()).toHaveAccessibleDescription(
      'Tu pourras réessayer à 14:31.',
    );
    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
  },
);

it.each(nothingToCompare)(
  'retient le bouton pour $name quoi que le jeune saisisse',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    await sendLimited(user, button);
    const field = screen.getByLabelText(scenario.field, { exact: true });

    await user.clear(field);
    await user.type(field, scenario.other);
    await user.click(button());

    expect(button()).toHaveAttribute('aria-disabled', 'true');
    expect(button()).toHaveAccessibleDescription(
      'Tu pourras réessayer à 14:31.',
    );
    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
  },
);

it.each(scenarios)(
  'garde le focus du bouton pour $name et n’envoie qu’une demande pendant l’envoi au clic',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    const sent = button();
    const held = holdResponse();
    server.use(http.post(scenario.path, held.resolver, { once: true }));

    await user.click(sent);
    await user.click(sent);

    expect(sent).toHaveAttribute('aria-disabled', 'true');
    // A disabled button gives the focus back to the page: only `aria-disabled` keeps a keyboard user in place.
    expect(sent).toBeEnabled();
    expect(sent).toHaveFocus();
    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
    await act(async () => held.release(rateLimitedResponse(30)));
  },
);

it.each(forms)(
  'n’envoie qu’une demande pour $name pendant l’envoi à la touche Entrée dans un champ',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    const sent = button();
    const held = holdResponse();
    server.use(http.post(scenario.path, held.resolver, { once: true }));

    await user.click(sent);
    await user.type(
      screen.getByLabelText(scenario.field, { exact: true }),
      '{Enter}',
    );

    expect(sent).toHaveAttribute('aria-disabled', 'true');
    expect(sent).toBeEnabled();
    await settled();
    expect(requests.of('POST', scenario.path)).toHaveLength(1);
    await act(async () => held.release(rateLimitedResponse(30)));
    await screen.findByText(/Tu pourras réessayer/);
    expect(sent).toHaveAccessibleDescription('Tu pourras réessayer à 14:31.');
  },
);

it.each(scenarios)(
  'garde le message de panne du 503 pour $name, sans heure ni retenue',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    server.use(
      http.post(scenario.path, () =>
        problemResponse(503, 'UNAVAILABLE', { 'Retry-After': '5' }),
      ),
    );

    await user.click(button());

    await vi.waitFor(() =>
      expect(requests.of('POST', scenario.path)).toHaveLength(1),
    );
    await settled();
    expect(screen.queryByText(/Tu pourras réessayer/)).not.toBeInTheDocument();
    expect(button()).not.toHaveAttribute('aria-disabled', 'true');
    expect(button()).not.toHaveAccessibleDescription();
  },
);

/** The buttons whose request sends an email: the API answers 503 while an email of the same address is being sent. */
const emailSenders = scenarios.filter(({ path }) =>
  [
    '/api/auth/signup',
    '/api/auth/password/forgot',
    '/api/auth/google/email',
  ].includes(path),
);

it.each(emailSenders)(
  'annonce la panne du 503 de l’envoi pour $name et laisse envoyer de nouveau',
  async (scenario) => {
    const { user, requests, button } = await show(scenario);
    server.use(
      http.post(scenario.path, () => problemResponse(503, 'UNAVAILABLE')),
    );

    await user.click(button());

    await vi.waitFor(() =>
      expect(requests.of('POST', scenario.path)).toHaveLength(1),
    );
    await settled();
    expect(
      screen
        .getAllByRole('alert')
        .map((alert) => alert.textContent)
        .join(' '),
    ).toMatch(/temporairement indisponible/);
    expect(button()).not.toHaveAttribute('aria-disabled', 'true');
    await user.click(button());
    await vi.waitFor(() =>
      expect(requests.of('POST', scenario.path)).toHaveLength(2),
    );
  },
);

it('n’envoie pas le départ vers Google pendant une connexion en cours, et le bouton garde le focus', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(authScreens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Email ou pseudo'),
    'jeune_demo',
  );
  await user.type(screen.getByLabelText('Mot de passe'), password);
  const requests = recordRequests();
  const held = holdResponse();
  server.use(http.post('/api/auth/login', held.resolver, { once: true }));
  await user.click(screen.getByRole('button', { name: 'Se connecter' }));
  const google = screen.getByRole('button', { name: 'Continuer avec Google' });

  await user.click(google);

  expect(google).toHaveAttribute('aria-disabled', 'true');
  expect(google).toBeEnabled();
  await settled();
  expect(requests.of('POST', '/api/auth/google/start')).toHaveLength(0);
  await act(async () => held.release(rateLimitedResponse(30)));
});

it('garde le message de panne du 503 de la connexion, avec son texte propre, sans heure ni retenue', async () => {
  readsSessionAs(anonymousResponse);
  renderWithProviders(authScreens, { route: '/connexion' });
  const user = userEvent.setup();
  await user.type(
    await screen.findByLabelText('Email ou pseudo'),
    'jeune_demo',
  );
  await user.type(screen.getByLabelText('Mot de passe'), password);
  server.use(
    http.post('/api/auth/login', () =>
      problemResponse(503, 'UNAVAILABLE', { 'Retry-After': '5' }),
    ),
  );

  await user.click(screen.getByRole('button', { name: 'Se connecter' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Connexion temporairement indisponible. Réessaie plus tard.',
  );
  expect(screen.queryByText(/Tu pourras réessayer/)).not.toBeInTheDocument();
  // Only the status of the loading is left: a wait that was not asked for has no region.
  expect(screen.getAllByRole('status')).toHaveLength(1);
});
