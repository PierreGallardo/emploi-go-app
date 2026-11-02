import { act, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import {
  anonymousResponse,
  problemResponse,
  rateLimitedResponse,
  testRegistration,
  sessionResponse,
} from '@/test/handlers/auth';
import { holdNextRead, readsSessionAs } from '@/test/auth';
import { authScreens, codeScreenRoute } from '@/test/auth-screens';
import { renderWithProviders } from '@/test/render';
import { holdResponse, postTo, recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';

const email = 'jeune@exemple.fr';

type User = ReturnType<typeof userEvent.setup>;

const codeField = () =>
  screen.getByRole('textbox', { name: 'Code à 6 chiffres reçu par email' });
const resendButton = () =>
  screen.getByRole('button', { name: 'Renvoyer le code' });
const statusTexts = () =>
  screen.getAllByRole('status').map((status) => status.textContent);

async function show(route = codeScreenRoute(email)) {
  const rendered = renderWithProviders(authScreens, { route });
  await screen.findByRole('textbox', {
    name: 'Code à 6 chiffres reçu par email',
  });
  return { ...rendered, user: userEvent.setup() };
}

/** The sixth digit sends the code: there is no button. */
async function submitCode(user: User, code = '123456') {
  await user.clear(codeField());
  await user.type(codeField(), code);
}

function refuseConfirmationWith(status: number, code: string) {
  server.use(
    http.post('/api/auth/signup/confirm', () => problemResponse(status, code)),
  );
}

beforeEach(() => {
  readsSessionAs(anonymousResponse);
});
afterEach(() => {
  vi.useRealTimers();
});

it('renvoie à l’inscription quand l’écran est ouvert sans état', async () => {
  renderWithProviders(authScreens, { route: '/inscription/code' });

  expect(
    await screen.findByRole('heading', { name: 'Créer un compte' }),
  ).toBeVisible();
});

it.each([
  { cas: 'sans email', state: { registration: testRegistration } },
  { cas: 'avec un email mal formé', state: { email: 'pas-un-email' } },
])('renvoie à l’inscription quand l’état est $cas', async ({ state }) => {
  renderWithProviders(authScreens, {
    route: { pathname: '/inscription/code', state },
  });

  expect(
    await screen.findByRole('heading', { name: 'Créer un compte' }),
  ).toBeVisible();
});

it('renvoie à la carte quand une session existe déjà', async () => {
  readsSessionAs(() => sessionResponse());
  renderWithProviders(authScreens, { route: codeScreenRoute(email) });

  expect(await screen.findByRole('heading', { name: 'Carte' })).toBeVisible();
});

it('montre un squelette sans champ pendant la lecture de la session, puis le formulaire sans passer par la carte', async () => {
  const read = holdNextRead();
  renderWithProviders(authScreens, { route: codeScreenRoute(email) });

  expect(await screen.findByRole('status')).toHaveTextContent('Chargement…');
  expect(
    screen.getByRole('heading', { name: 'Vérifie tes emails' }),
  ).toBeVisible();
  expect(screen.queryByRole('textbox')).toBeNull();
  await act(async () => read(anonymousResponse()));

  expect(
    await screen.findByRole('textbox', {
      name: 'Code à 6 chiffres reçu par email',
    }),
  ).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Carte' })).toBeNull();
});

it('dit à quelle adresse l’email a été envoyé', async () => {
  await show();

  expect(
    screen.getByText(new RegExp(`^Email envoyé à ${email} ·`)),
  ).toBeVisible();
  expect(
    screen.getByRole('link', { name: 'Modifier l’adresse email' }),
  ).toHaveAttribute('href', '/inscription');
});

it('propose un seul champ numérique, rempli par le code reçu', async () => {
  await show();

  expect(codeField()).toHaveAttribute('inputmode', 'numeric');
  expect(codeField()).toHaveAttribute('autocomplete', 'one-time-code');
});

it('envoie le code dès le sixième chiffre et ignore un septième', async () => {
  const bodies = postTo('/api/auth/signup/confirm', () =>
    sessionResponse(null),
  );
  const { user } = await show();

  await submitCode(user, '1234567');

  await screen.findByRole('heading', { name: 'Choisis ton pseudo' });
  expect(bodies).toEqual([{ code: '123456', registration: testRegistration }]);
});

it('accepte un code collé avec ses séparateurs', async () => {
  const bodies = postTo('/api/auth/signup/confirm', () =>
    sessionResponse(null),
  );
  const { user } = await show();

  await user.click(codeField());
  await user.paste('12 34-56');

  await screen.findByRole('heading', { name: 'Choisis ton pseudo' });
  expect(bodies).toMatchObject([{ code: '123456' }]);
});

it('ignore les lettres saisies dans le code', async () => {
  const { user } = await show();

  await user.type(codeField(), '12a34');

  expect(codeField()).toHaveValue('1234');
});

it('refuse un code incomplet validé avec Entrée, sans rien envoyer', async () => {
  const requests = recordRequests();
  const { user } = await show();

  await user.type(codeField(), '1234{Enter}');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Saisis les 6 chiffres du code reçu.',
  );
  expect(codeField()).toBeInvalid();
  await settled();
  expect(requests.of('POST', '/api/auth/signup/confirm')).toHaveLength(0);
});

it('ouvre la session reçue et mène au choix du pseudo', async () => {
  postTo('/api/auth/signup/confirm', () => sessionResponse(null));
  const { user } = await show();

  await submitCode(user);

  expect(
    await screen.findByRole('heading', { name: 'Choisis ton pseudo' }),
  ).toBeVisible();
});

it('vide le cache privé à l’ouverture de la session reçue', async () => {
  postTo('/api/auth/signup/confirm', () => sessionResponse(null));
  const { user, client } = await show();
  client.setQueryData(['private', 'saved'], 'ancienne donnée');

  await submitCode(user);
  await screen.findByRole('heading', { name: 'Choisis ton pseudo' });

  expect(client.getQueryData(['private', 'saved'])).toBeUndefined();
});

it.each([
  {
    code: 'INVALID_CODE',
    status: 400,
    message: 'Code incorrect. Vérifie le dernier email reçu.',
  },
  {
    code: 'CODE_EXPIRED',
    status: 410,
    message: 'Code expiré. Demande un nouveau code.',
  },
  {
    code: 'CODE_REPLACED',
    status: 410,
    message: 'Ce code a été remplacé. Utilise le dernier email reçu.',
  },
  {
    code: 'CODE_USED',
    status: 410,
    message: 'Code déjà utilisé. Connecte-toi.',
  },
  {
    code: 'CODE_ATTEMPTS_EXCEEDED',
    status: 410,
    message: 'Trop d’essais. Demande un nouveau code.',
  },
])(
  'explique le refus $code, le relie au champ du code et le vide pour une nouvelle saisie',
  async ({ code, status, message }) => {
    refuseConfirmationWith(status, code);
    const { user } = await show();

    await submitCode(user);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(message);
    expect(codeField()).toBeInvalid();
    expect(codeField()).toHaveAccessibleDescription(
      expect.stringContaining(message),
    );
    expect(codeField()).toHaveValue('');
  },
);

it.each([
  {
    code: 'ACCOUNT_ALREADY_EXISTS',
    status: 409,
    message: 'Tu as déjà un compte. Connecte-toi.',
  },
  {
    code: 'UNAVAILABLE',
    status: 503,
    message:
      'Action temporairement indisponible. Attends une minute puis réessaie.',
  },
])(
  'explique le refus $code sans accuser le champ du code, et vide les cases pour une nouvelle saisie',
  async ({ code, status, message }) => {
    refuseConfirmationWith(status, code);
    const { user } = await show();

    await submitCode(user);

    expect(await screen.findByRole('alert')).toHaveTextContent(message);
    expect(codeField()).toBeValid();
    expect(codeField()).toHaveValue('');
  },
);

it('retire le message d’erreur du champ dès que le code change', async () => {
  refuseConfirmationWith(400, 'INVALID_CODE');
  const { user } = await show();
  await submitCode(user);
  await screen.findByRole('alert');
  expect(codeField()).toBeInvalid();

  await user.type(codeField(), '7');

  expect(codeField()).not.toHaveAccessibleDescription(
    expect.stringContaining('Code incorrect'),
  );
});

it.each([
  { code: 'CODE_USED', status: 410 },
  { code: 'ACCOUNT_ALREADY_EXISTS', status: 409 },
])('mène à la connexion depuis le refus $code', async ({ code, status }) => {
  refuseConfirmationWith(status, code);
  const { user } = await show();

  await submitCode(user);

  expect(
    await screen.findByRole('link', { name: 'Me connecter' }),
  ).toHaveAttribute('href', '/connexion');
});

it.each(['INVALID_CODE', 'CODE_REPLACED'])(
  'ne donne pas le focus au renvoi après %s',
  async (code) => {
    refuseConfirmationWith(400, code);
    const { user } = await show();

    await submitCode(user);

    await screen.findByRole('alert');
    expect(resendButton()).not.toHaveFocus();
  },
);

it.each(['CODE_EXPIRED', 'CODE_ATTEMPTS_EXCEEDED'])(
  'donne le focus à « Renvoyer le code » après %s',
  async (code) => {
    refuseConfirmationWith(410, code);
    const { user } = await show();

    await submitCode(user);

    await screen.findByRole('alert');
    expect(resendButton()).toHaveFocus();
  },
);

it('redonne le focus au champ du code après un code faux', async () => {
  refuseConfirmationWith(400, 'INVALID_CODE');
  const { user } = await show();

  await submitCode(user);

  await screen.findByRole('alert');
  expect(codeField()).toHaveFocus();
});

it('n’envoie qu’une fois le code validé avec Entrée juste après le sixième chiffre', async () => {
  const held = holdResponse();
  server.use(
    http.post('/api/auth/signup/confirm', held.resolver, { once: true }),
  );
  const requests = recordRequests();
  const { user } = await show();

  await user.type(codeField(), '123456{Enter}');

  await settled();
  expect(requests.of('POST', '/api/auth/signup/confirm')).toHaveLength(1);
  await act(async () => held.release(sessionResponse()));
});

it('renvoie le code retapé après un refus', async () => {
  refuseConfirmationWith(400, 'INVALID_CODE');
  const requests = recordRequests();
  const { user } = await show();
  await submitCode(user);
  await screen.findByRole('alert');

  await user.type(codeField(), '654321');

  await vi.waitFor(() =>
    expect(requests.of('POST', '/api/auth/signup/confirm')).toHaveLength(2),
  );
});

it('désactive le code et annonce la vérification pendant l’envoi', async () => {
  const held = holdResponse();
  server.use(
    http.post('/api/auth/signup/confirm', held.resolver, { once: true }),
  );
  const { user } = await show();
  const regions = screen.getAllByRole('status');

  await submitCode(user);

  expect(codeField()).toBeDisabled();
  expect(statusTexts()).toContain('Vérification…');
  expect(regions.filter((region) => !region.isConnected)).toEqual([]);
  await act(async () => held.release(sessionResponse()));
});

it('n’envoie pas une seconde confirmation pendant l’envoi du code', async () => {
  const held = holdResponse();
  server.use(
    http.post('/api/auth/signup/confirm', held.resolver, { once: true }),
  );
  const requests = recordRequests();
  const { user } = await show();

  await submitCode(user);

  await settled();
  expect(requests.of('POST', '/api/auth/signup/confirm')).toHaveLength(1);
  await act(async () => held.release(sessionResponse()));
});

/** Refuses the code as expired, then asks for a new one: returns the bodies received and the status regions before the answer. */
async function resendAfterExpiredCode() {
  refuseConfirmationWith(410, 'CODE_EXPIRED');
  const bodies: string[] = [];
  server.use(
    http.post('/api/auth/signup/resend', async ({ request }) => {
      bodies.push(await request.text());
      return new HttpResponse(null, { status: 202 });
    }),
  );
  const { user } = await show();
  await submitCode(user);
  await screen.findByRole('alert');
  const regions = screen.getAllByRole('status');

  await user.click(resendButton());

  return { bodies, regions };
}

it('demande un nouveau code pour l’inscription du navigateur', async () => {
  const { bodies } = await resendAfterExpiredCode();

  await screen.findByText('Email renvoyé.');
  expect(bodies).toEqual([JSON.stringify({ registration: testRegistration })]);
});

it('annonce « Email renvoyé. » dans la région existante, sans dire le code expiré', async () => {
  const { regions } = await resendAfterExpiredCode();

  const message = await screen.findByText('Email renvoyé.');
  expect(screen.getAllByRole('status')).toEqual(regions);
  expect(regions.filter((region) => region.contains(message))).toHaveLength(1);
  expect(
    screen.queryByText('Code expiré. Demande un nouveau code.'),
  ).toBeNull();
});

it('repart d’un champ de code vide où le focus se pose après le renvoi', async () => {
  await resendAfterExpiredCode();

  await screen.findByText('Email renvoyé.');
  expect(codeField()).toHaveValue('');
  expect(codeField()).toHaveFocus();
});

it('annonce la panne de l’envoi du nouveau code et laisse redemander avec la même saisie', async () => {
  const answers = [
    () => problemResponse(503, 'UNAVAILABLE'),
    () => new HttpResponse(null, { status: 202 }),
  ];
  const bodies: string[] = [];
  server.use(
    http.post('/api/auth/signup/resend', async ({ request }) => {
      bodies.push(await request.text());
      return answers[bodies.length - 1]!();
    }),
  );
  const { user } = await show();

  await user.click(resendButton());
  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Action temporairement indisponible. Attends une minute puis réessaie.',
  );
  await user.click(resendButton());

  expect(await screen.findByText('Email renvoyé.')).toBeVisible();
  const body = JSON.stringify({ registration: testRegistration });
  expect(bodies).toEqual([body, body]);
});

async function showAtFixedTime() {
  const shown = await show();
  vi.useFakeTimers({
    now: new Date(2026, 9, 17, 14, 30, 10),
    shouldAdvanceTime: true,
    toFake: ['Date', 'setTimeout', 'clearTimeout'],
  });
  return {
    ...shown,
    user: userEvent.setup({ advanceTimers: vi.advanceTimersByTime }),
  };
}

const blocked = () =>
  problemResponse(429, 'CODES_BLOCKED', { 'Retry-After': '3600' });
const blockedUntil = 'Réessaie à 15:31.';

it('dit à quelle heure ressaisir le code et demander un nouveau code après une heure de blocage, sans compte à rebours', async () => {
  const { user } = await showAtFixedTime();
  server.use(http.post('/api/auth/signup/confirm', blocked));

  await submitCode(user);

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Trop d’essais.');
  expect(alert).toHaveTextContent(blockedUntil);
  expect(screen.queryByText(/Tu pourras réessayer/)).toBeNull();
});

it('suspend la confirmation et la demande d’un nouveau code pendant le blocage, sans rien envoyer', async () => {
  const { user } = await showAtFixedTime();
  server.use(http.post('/api/auth/signup/confirm', blocked));
  await submitCode(user);
  await screen.findByRole('alert');
  const requests = recordRequests();

  await submitCode(user, '654321');
  await user.click(resendButton());

  expect(codeField()).toHaveAccessibleDescription(blockedUntil);
  expect(resendButton()).toHaveAttribute('aria-disabled', 'true');
  expect(resendButton()).toHaveAccessibleDescription(blockedUntil);
  await settled();
  expect(requests.received).toHaveLength(0);
});

it('rend la main à l’heure dite et l’annonce une seule fois', async () => {
  const { user } = await showAtFixedTime();
  server.use(http.post('/api/auth/signup/confirm', blocked));
  await submitCode(user);
  await screen.findByRole('alert');
  await act(() => vi.advanceTimersByTimeAsync(3_599_000));
  expect(screen.queryByText('Tu peux réessayer.')).toBeNull();

  await act(() => vi.advanceTimersByTimeAsync(1_000));

  expect(screen.getAllByText('Tu peux réessayer.')).toHaveLength(1);
  expect(codeField()).not.toHaveAccessibleDescription(blockedUntil);
  expect(resendButton()).not.toHaveAttribute('aria-disabled', 'true');
});

it('suspend aussi la confirmation quand c’est la demande d’un nouveau code qui est bloquée', async () => {
  const { user } = await showAtFixedTime();
  server.use(http.post('/api/auth/signup/resend', blocked));

  await user.click(resendButton());

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Trop d’essais.');
  expect(alert).toHaveTextContent(blockedUntil);
  expect(resendButton()).toHaveAttribute('aria-disabled', 'true');
  expect(codeField()).toHaveAccessibleDescription(blockedUntil);
  expect(screen.getAllByText(blockedUntil)).toHaveLength(1);
});

it('ne suspend que la confirmation quand un quota, et non le blocage, limite la saisie, même pour une longue attente', async () => {
  const { user } = await showAtFixedTime();
  server.use(
    http.post('/api/auth/signup/confirm', () => rateLimitedResponse(3600)),
  );

  await submitCode(user);

  await screen.findByRole('alert');
  expect(codeField()).toHaveAccessibleDescription(/Tu pourras réessayer/);
  expect(resendButton()).not.toHaveAttribute('aria-disabled', 'true');
});

it('ne suspend que la confirmation, avec le message habituel, quand un quota court limite la saisie', async () => {
  const { user } = await showAtFixedTime();
  server.use(
    http.post('/api/auth/signup/confirm', () => rateLimitedResponse(60)),
  );

  await submitCode(user);

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Trop de tentatives. Réessaie plus tard.');
  expect(alert).toHaveTextContent('Tu pourras réessayer à 14:32.');
  expect(codeField()).toHaveAccessibleDescription(
    'Tu pourras réessayer à 14:32.',
  );
  expect(resendButton()).not.toHaveAttribute('aria-disabled', 'true');
});

it('dit qu’un code a déjà été demandé il y a moins d’une minute, avec l’heure du prochain renvoi, sans alerte d’erreur', async () => {
  const { user } = await showAtFixedTime();
  server.use(
    http.post('/api/auth/signup/resend', () =>
      problemResponse(429, 'MAIL_RECENTLY_SENT', { 'Retry-After': '60' }),
    ),
  );

  await user.click(resendButton());

  await screen.findByText(
    'Un code a déjà été demandé pour cette adresse il y a moins d’une minute. Regarde tes emails.',
  );
  expect(
    screen.getByText('Tu pourras demander un nouveau code à 14:32.'),
  ).toBeVisible();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('button', { name: /^Renvoyer/ })).toHaveAttribute(
    'aria-disabled',
    'true',
  );
});

it('ne suspend que le renvoi, avec l’heure, quand c’est l’envoi du code qui est limité', async () => {
  const { user } = await showAtFixedTime();
  server.use(
    http.post('/api/auth/signup/resend', () => rateLimitedResponse(60)),
  );

  await user.click(resendButton());

  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent('Trop de tentatives. Réessaie plus tard.');
  expect(alert).toHaveTextContent(
    'Tu pourras demander un nouveau code à 14:32.',
  );
  expect(resendButton()).toHaveAttribute('aria-disabled', 'true');
  expect(codeField()).not.toHaveAccessibleDescription(/Tu pourras/);
});

it('dit que l’inscription n’est plus en cours sur cette page et mène à l’inscription', async () => {
  refuseConfirmationWith(410, 'NO_PENDING_REGISTRATION');
  const { user } = await show();

  await submitCode(user);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ton inscription n’est plus en cours sur cette page. Recommence l’inscription.',
  );
  expect(
    screen.getByRole('link', { name: 'Recommencer l’inscription' }),
  ).toHaveAttribute('href', '/inscription');
});

it('mène à l’inscription quand le renvoi ne trouve plus d’inscription dans ce navigateur', async () => {
  server.use(
    http.post('/api/auth/signup/resend', () =>
      problemResponse(410, 'NO_PENDING_REGISTRATION'),
    ),
  );
  const { user } = await show();

  await user.click(resendButton());

  expect(
    await screen.findByRole('link', { name: 'Recommencer l’inscription' }),
  ).toHaveAttribute('href', '/inscription');
});
