import { act, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { afterEach, expect, it, vi } from 'vitest';
import { problemResponse } from '@/test/handlers/auth';
import { answersOn, recoveryLink } from '@/test/auth';
import { renderWithProviders } from '@/test/render';
import { holdResponse, recordRequests } from '@/test/requests';
import { server, settled } from '@/test/server';
import { sessionKey } from '../../queries';
import { ForgotPasswordScreen } from './forgot-password-screen';
import { ResetPasswordScreen } from './reset-password-screen';

const forgotPath = '/api/auth/password/forgot';
const resetPath = '/api/auth/password/reset';
const password = ' Une phrase fictive 1 avec accents 🔑 ';

const accepted = () =>
  new HttpResponse(null, { status: 202, headers: { 'Retry-After': '45' } });
const changed = () => HttpResponse.json({ changed: true });

function openResetLink() {
  window.history.replaceState(
    null,
    '',
    `/reinitialiser-mot-de-passe#${recoveryLink}`,
  );
}

async function fillPassword(user: ReturnType<typeof userEvent.setup>) {
  await user.click(
    screen.getByLabelText('Nouveau mot de passe', { exact: true }),
  );
  await user.paste(password);
  await user.click(screen.getByLabelText('Confirmer le nouveau mot de passe'));
  await user.paste(password);
}

async function changePassword(user: ReturnType<typeof userEvent.setup>) {
  await fillPassword(user);
  await user.click(
    screen.getByRole('button', { name: 'Changer mon mot de passe' }),
  );
}

afterEach(() => {
  vi.useRealTimers();
  window.history.replaceState(null, '', '/');
});

it('n’envoie pas la demande de lien sans email valide', async () => {
  answersOn(forgotPath, accepted);
  const requests = recordRequests();
  renderWithProviders(<ForgotPasswordScreen />);
  const user = userEvent.setup();
  await user.click(screen.getByRole('button', { name: 'Envoyer la demande' }));

  expect(
    await screen.findByText('Indique une adresse email valide.'),
  ).toBeVisible();
  await settled();
  expect(requests.of('POST', forgotPath)).toHaveLength(0);
});

const neutral =
  'Si un compte correspond à cette adresse, un message sera envoyé. Vérifie aussi les courriers indésirables. Le lien est valable 30 minutes.';

async function requestLink(answer: () => Response) {
  const bodies = answersOn(forgotPath, answer);
  const requests = recordRequests();
  renderWithProviders(<ForgotPasswordScreen />);
  const user = userEvent.setup();
  await user.type(screen.getByLabelText('Email'), 'absent@example.invalid');
  await user.click(screen.getByRole('button', { name: 'Envoyer la demande' }));
  await screen.findByRole('heading', { name: 'Vérifie tes emails' });
  return { bodies, requests, user };
}

it('remplace le formulaire par le même message public après une demande valide, avec le focus sur le titre', async () => {
  await requestLink(accepted);

  expect(
    screen.getByRole('heading', { name: 'Vérifie tes emails' }),
  ).toHaveFocus();
  expect(screen.getByText(neutral, { exact: false })).toBeVisible();
  expect(screen.queryByLabelText('Email')).toBeNull();
});

it('envoie seulement l’adresse saisie à la route de demande', async () => {
  const { bodies } = await requestLink(accepted);

  expect(bodies).toEqual([{ email: 'absent@example.invalid' }]);
});

it('n’ouvre aucune session après la demande de lien', async () => {
  const { requests } = await requestLink(accepted);

  await settled();
  // Only the request for the link is written: no login, no session opened.
  expect(
    requests.received
      .filter(({ method }) => method === 'POST')
      .map(({ path }) => path),
  ).toEqual([forgotPath]);
});

it('propose de revenir à la connexion depuis le message de demande', async () => {
  await requestLink(accepted);

  expect(
    screen.getByRole('link', { name: 'Revenir à la connexion' }),
  ).toHaveAttribute('href', '/connexion');
});

it('retient le renvoi dès la demande acceptée, pendant l’attente annoncée par l’API', async () => {
  await requestLink(accepted);

  expect(
    screen.getByRole('button', { name: 'Renvoyer dans 45 s' }),
  ).toHaveAttribute('aria-disabled', 'true');
});

it('une demande de lien refusée parce qu’un email vient d’être envoyé affiche le résultat d’une demande acceptée et garde l’attente', async () => {
  const { requests } = await requestLink(() =>
    problemResponse(429, 'MAIL_RECENTLY_SENT', { 'Retry-After': '45' }),
  );

  expect(screen.getByText(neutral, { exact: false })).toBeVisible();
  expect(screen.queryByRole('alert')).toBeNull();
  expect(
    screen.getByRole('button', { name: 'Renvoyer dans 45 s' }),
  ).toHaveAttribute('aria-disabled', 'true');
  expect(requests.of('POST', forgotPath)).toHaveLength(1);
});

it('renvoie le lien à la fin de l’attente, puis attend de nouveau le délai annoncé', async () => {
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  const { bodies, user } = await requestLink(accepted);
  await act(() => vi.advanceTimersByTimeAsync(45_000));

  await user.click(screen.getByRole('button', { name: 'Renvoyer le lien' }));

  expect(await screen.findByText('Email renvoyé.')).toBeVisible();
  expect(bodies).toHaveLength(2);
  expect(
    screen.getByRole('button', { name: 'Renvoyer dans 45 s' }),
  ).toHaveAttribute('aria-disabled', 'true');
});

it('« Modifier » rend le formulaire avec l’adresse demandée', async () => {
  const { user } = await requestLink(accepted);

  await user.click(
    screen.getByRole('button', { name: 'Modifier l’adresse email' }),
  );

  expect(await screen.findByLabelText('Email')).toHaveValue(
    'absent@example.invalid',
  );
});

it('retire le secret du lien de l’adresse dès l’ouverture de l’écran', async () => {
  openResetLink();
  renderWithProviders(<ResetPasswordScreen />);
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  expect(window.location.hash).toBe('');
});

it('n’envoie rien tant que la saisie du nouveau mot de passe n’est pas valide', async () => {
  openResetLink();
  answersOn(resetPath, changed);
  const requests = recordRequests();
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await user.click(
    screen.getByRole('button', { name: 'Changer mon mot de passe' }),
  );

  await settled();
  expect(requests.of('POST', resetPath)).toHaveLength(0);
});

it('change le mot de passe et affiche la demande de connexion normale', async () => {
  openResetLink();
  answersOn(resetPath, changed);
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);

  expect(
    await screen.findByRole('heading', { name: 'Mot de passe changé' }),
  ).toBeVisible();
  // « Chargement terminé. » of the reading of the link may still be there, before the message.
  expect(screen.getAllByRole('status').at(-1)).toHaveTextContent(
    'Connecte-toi avec ton nouveau mot de passe',
  );
  expect(
    screen.queryByLabelText('Nouveau mot de passe'),
  ).not.toBeInTheDocument();
});

it('vide le cache privé après le changement de mot de passe', async () => {
  openResetLink();
  answersOn(resetPath, changed);
  const { client } = renderWithProviders(<ResetPasswordScreen />);
  client.setQueryData(['private'], { fixture: true });
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);
  await screen.findByRole('heading', { name: 'Mot de passe changé' });

  expect(client.getQueryData(['private'])).toBeUndefined();
  expect(client.getQueryData(sessionKey)).toBeNull();
});

it('envoie le mot de passe à la route de réinitialisation, sans identifiant de requête', async () => {
  openResetLink();
  const bodies = answersOn(resetPath, changed);
  const requests = recordRequests();
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);
  await screen.findByRole('heading', { name: 'Mot de passe changé' });

  expect(
    requests.received
      .filter(({ method }) => method === 'POST')
      .map(({ path }) => path),
  ).toEqual([resetPath]);
  expect(bodies[0]).toMatchObject({ password });
  expect(bodies[0]).not.toHaveProperty('requestId');
});

it('une réponse perdue affiche l’erreur et garde la saisie', async () => {
  openResetLink();
  const bodies = answersOn(resetPath, () => HttpResponse.error(), changed);
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Impossible de confirmer la demande',
  );
  expect(
    screen.getByLabelText('Nouveau mot de passe', { exact: true }),
  ).toHaveValue(password);
  expect(bodies).toHaveLength(1);
});

it('le nouvel envoi après une réponse perdue reprend la même demande sans identifiant', async () => {
  openResetLink();
  const bodies = answersOn(resetPath, () => HttpResponse.error(), changed);
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });
  await changePassword(user);
  await screen.findByRole('alert');

  await user.click(
    screen.getByRole('button', { name: 'Changer mon mot de passe' }),
  );

  await screen.findByRole('heading', { name: 'Mot de passe changé' });
  expect(bodies).toHaveLength(2);
  expect(bodies[1]).toEqual(bodies[0]);
  expect(bodies[0]).not.toHaveProperty('requestId');
});

it('un lien déjà servi renvoie à la connexion avec le nouveau mot de passe, sans formulaire', async () => {
  openResetLink();
  answersOn(resetPath, () => problemResponse(410, 'USED_LINK'));
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Ce lien a déjà servi. Si tu viens de changer ton mot de passe, connecte-toi avec le nouveau.',
  );
  expect(
    screen.queryByRole('button', { name: 'Changer mon mot de passe' }),
  ).not.toBeInTheDocument();
  expect(screen.getByRole('link', { name: 'Se connecter' })).toHaveAttribute(
    'href',
    '/connexion',
  );
  expect(
    screen.getByRole('link', { name: 'Demander un nouveau lien' }),
  ).toHaveAttribute('href', '/mot-de-passe-oublie');
});

it('un lien expiré reçoit un message contrôlé avec une nouvelle récupération', async () => {
  openResetLink();
  answersOn(resetPath, () => problemResponse(410, 'EXPIRED_LINK'));
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });

  await changePassword(user);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Demande un nouveau lien',
  );
  expect(
    screen.queryByRole('button', { name: 'Changer mon mot de passe' }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByRole('link', { name: 'Demander un nouveau lien' }),
  ).toHaveAttribute('href', '/mot-de-passe-oublie');
});

it('ignore une ancienne réponse lorsque le navigateur ouvre un autre lien', async () => {
  openResetLink();
  const held = holdResponse();
  server.use(http.post(resetPath, held.resolver, { once: true }));
  renderWithProviders(<ResetPasswordScreen />);
  const user = userEvent.setup();
  await screen.findByLabelText('Nouveau mot de passe', { exact: true });
  await changePassword(user);

  const next = `${'87654321-1234-4234-8234-123456789abc'}.${'b'.repeat(43)}`;
  await act(async () => {
    window.location.hash = next;
    window.dispatchEvent(new HashChangeEvent('hashchange'));
  });
  await waitFor(() => expect(window.location.hash).toBe(''));
  await act(async () => {
    held.release(changed());
  });

  expect(
    screen.getByRole('heading', { name: 'Nouveau mot de passe' }),
  ).toBeVisible();
  expect(
    screen.queryByRole('heading', { name: 'Mot de passe changé' }),
  ).not.toBeInTheDocument();
  expect(
    screen.getByLabelText('Nouveau mot de passe', { exact: true }),
  ).toHaveValue('');
});
